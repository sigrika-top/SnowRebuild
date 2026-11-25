package snow.rebuild;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class WindowsApp {
    private record Capture(GraphicsDevice monitor, Rectangle area) {}

    private final Recognizer recognizer;
    private final JFrame frame = new JFrame("雪境重建助手");
    private final JTextArea result = new JTextArea(10, 31);
    private final JLabel status = new JLabel(" ");
    private final JButton select = new JButton("框选游戏");
    private final JButton analyze = new JButton("分析当前画面");
    private final JButton open = new JButton("打开截图");
    private Capture capture;

    public static void main(String[] args) throws Exception {
        var reader = new Recognizer(WindowsApp.class.getResourceAsStream("/shapes.txt"));
        if (args.length == 3 && "--analyze".equals(args[0])) {
            var image = ImageIO.read(Path.of(args[1]).toFile());
            Files.writeString(Path.of(args[2]), analyze(reader, image));
            return;
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        EventQueue.invokeLater(() -> new WindowsApp(reader).show());
    }

    private WindowsApp(Recognizer recognizer) {
        this.recognizer = recognizer;
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        try {
            frame.setIconImage(ImageIO.read(WindowsApp.class.getResource("/app_icon.png")));
        } catch (IOException ignored) {}

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.add(select);
        toolbar.add(analyze);
        toolbar.add(open);
        analyze.setEnabled(false);
        select.addActionListener(e -> selectRegion());
        analyze.addActionListener(e -> captureScreen());
        open.addActionListener(e -> openImage());
        frame.getRootPane().setDefaultButton(analyze);

        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 16));
        result.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));
        result.setText("打开横屏重建小游戏，点「框选游戏」，拖出完整游戏画面。\n\n框选一次后，可以重复点「分析当前画面」。移动或缩放游戏窗口后，请重新框选。");
        JPanel content = new JPanel(new BorderLayout(0, 9));
        content.setBorder(BorderFactory.createEmptyBorder(12,12,10,12));
        content.add(toolbar,BorderLayout.NORTH);
        content.add(new JScrollPane(result),BorderLayout.CENTER);
        content.add(status,BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(390,240));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private void selectRegion() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        GraphicsDevice device = frame.getGraphicsConfiguration().getDevice();
        if (devices.length > 1) {
            String[] choices = new String[devices.length];
            int current = 0;
            for (int i=0; i<choices.length; i++) {
                Rectangle bounds = devices[i].getDefaultConfiguration().getBounds();
                choices[i] = "显示器 " + (i+1) + "  (" + bounds.width + " × " + bounds.height + ")";
                if (devices[i].equals(device)) current=i;
            }
            Object choice=JOptionPane.showInputDialog(frame,"选择游戏所在的显示器","框选游戏",
                    JOptionPane.PLAIN_MESSAGE,null,choices,choices[current]);
            if (choice==null) return;
            for (int i=0; i<choices.length; i++) if (choices[i].equals(choice)) device=devices[i];
        }
        GraphicsDevice target=device;
        Rectangle bounds=device.getDefaultConfiguration().getBounds();
        frame.setVisible(false);
        once(220, () -> {
            try {
                BufferedImage background=grab(target,bounds);
                showPicker(target,bounds,background);
            } catch (AWTException | RuntimeException error) {
                frame.setVisible(true);
                showResult("无法读取显示器画面，请重试或打开游戏截图。");
            }
        });
    }

    private void showPicker(GraphicsDevice monitor, Rectangle bounds, BufferedImage background) {
        JDialog picker = new JDialog(frame);
        picker.setUndecorated(true);
        picker.setAlwaysOnTop(true);
        picker.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JComponent preview = new JComponent() {
            Point start;
            Point end;

            {
                MouseAdapter mouse = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { start=e.getPoint(); end=start; repaint(); }
                    @Override public void mouseDragged(MouseEvent e) { end=e.getPoint(); repaint(); }
                    @Override public void mouseReleased(MouseEvent e) {
                        if (start==null) return;
                        end=e.getPoint();
                        Rectangle area=selection().intersection(new Rectangle(0,0,getWidth(),getHeight()));
                        if (area.width<420 || area.height<200 || area.width<area.height*1.55
                                || area.width>area.height*2.65) {
                            status.setText("请框选完整的横屏游戏画面。");
                            picker.dispose();
                            frame.setVisible(true);
                            return;
                        }
                        area.translate(bounds.x,bounds.y);
                        capture=new Capture(monitor,area);
                        picker.dispose();
                        status.setText("已选区域 " + area.width + " × " + area.height);
                        analyze.setEnabled(true);
                        frame.setVisible(true);
                    }
                };
                addMouseListener(mouse);
                addMouseMotionListener(mouse);
                setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
            }

            private Rectangle selection() {
                return new Rectangle(Math.min(start.x,end.x),Math.min(start.y,end.y),
                        Math.abs(start.x-end.x),Math.abs(start.y-end.y));
            }

            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g=(Graphics2D)graphics.create();
                try {
                    g.drawImage(background,0,0,getWidth(),getHeight(),null);
                    g.setColor(new Color(0,0,0,100));
                    g.fillRect(0,0,getWidth(),getHeight());
                    if (start!=null) {
                        Rectangle box=selection();
                        Graphics2D selected=(Graphics2D)g.create();
                        selected.clip(box);
                        selected.drawImage(background,0,0,getWidth(),getHeight(),null);
                        selected.dispose();
                        g.setColor(new Color(157,230,239));
                        g.setStroke(new BasicStroke(2));
                        g.draw(box);
                    }
                    g.setColor(new Color(20,34,44));
                    g.fillRoundRect(16,16,370,39,10,10);
                    g.setFont(new Font("Microsoft YaHei UI",Font.PLAIN,15));
                    g.setColor(Color.WHITE);
                    g.drawString("拖动框选完整游戏画面，Esc 取消",28,41);
                } finally { g.dispose(); }
            }
        };
        picker.setContentPane(preview);
        picker.getRootPane().registerKeyboardAction(e -> {
            picker.dispose();
            frame.setVisible(true);
        },KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        picker.setBounds(bounds);
        picker.setVisible(true);
    }

    private void captureScreen() {
        Capture target=capture;
        if (target==null || !analyze.isEnabled()) return;
        frame.setVisible(false);
        busy(true);
        once(220, () -> runAnalysis(() -> grab(target.monitor,target.area)));
    }

    private void openImage() {
        JFileChooser chooser=new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("屏幕截图 (PNG, JPG)", "png","jpg","jpeg"));
        if (chooser.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION) return;
        runAnalysis(() -> ImageIO.read(chooser.getSelectedFile()));
    }

    private void runAnalysis(Callable<BufferedImage> input) {
        busy(true);
        new SwingWorker<String,Void>() {
            @Override protected String doInBackground() throws Exception { return analyze(recognizer,input.call()); }
            @Override protected void done() {
                try { showResult(get()); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showResult("分析已中断。");
                } catch (ExecutionException e) {
                    Throwable root=e.getCause();
                    showResult(root instanceof IllegalArgumentException ? root.getMessage()
                            : "读取画面失败。请重新框选，或打开一张 PNG 或 JPG 截图。");
                } finally { busy(false); }
            }
        }.execute();
    }

    private void busy(boolean active) {
        select.setEnabled(!active);
        open.setEnabled(!active);
        analyze.setEnabled(!active && capture!=null);
        status.setText(active ? "正在分析…" : " ");
    }

    private void showResult(String message) {
        result.setText(message);
        result.setCaretPosition(0);
        if (!frame.isVisible()) frame.setVisible(true);
    }

    private static void once(int milliseconds, Runnable action) {
        Timer timer=new Timer(milliseconds,e -> action.run());
        timer.setRepeats(false);
        timer.start();
    }

    private static BufferedImage grab(GraphicsDevice device, Rectangle region) throws AWTException {
        var variants=new Robot(device).createMultiResolutionScreenCapture(region).getResolutionVariants();
        return (BufferedImage)variants.get(variants.size()-1);
    }

    private static String analyze(Recognizer reader, BufferedImage image) {
        if (image==null) throw new IllegalArgumentException("无法打开这张图片。请选择 PNG 或 JPG。");
        int w=image.getWidth(), h=image.getHeight();
        if (w<h*1.55 || w>h*2.65) {
            throw new IllegalArgumentException("画面比例不对。请框选横屏游戏区域，去掉标题栏和模拟器工具栏。");
        }
        Solver.Board board=reader.read(image.getRGB(0,0,w,h,null,0,w),w,h);
        Solver.Result route=Solver.solve(board);
        if (board.cards().length==0) return "手牌已用完。可以结束回合。";
        if (route.steps().isEmpty()) return Solver.explainNoProgress(board);

        StringBuilder text=new StringBuilder();
        int index=1;
        for (Solver.Step step : route.steps()) {
            var tile=board.tiles()[step.position()];
            text.append(index++).append(".  ").append(step.card()).append(" 点  ");
            text.append(switch (step.mode()) {
                case MOVE -> "前进 · " + Solver.MATERIAL[tile.material()] + (tile.yield()==0 ? "（封锁）" : "");
                case COLLECT -> "收集";
                case COMBO -> "收集（COMBO+）";
            }).append('\n');
        }
        if (route.points()>0) text.append("\n预计完成 +").append(route.points()).append(" 重建值");
        if (route.chests()>0) text.append("\n经过 ").append(route.chests()).append(" 个宝箱");
        return text.toString();
    }
}
