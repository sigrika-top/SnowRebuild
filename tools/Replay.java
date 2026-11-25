package snow.rebuild;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

final class Replay {
    public static void main(String[] args) throws Exception {
        Recognizer reader;
        boolean dynamic = Arrays.asList(args).contains("--dynamic");
        boolean nativeVision = Arrays.asList(args).contains("--native");
        BoardVision vision = BoardVision::scan;
        if (nativeVision) {
            Class.forName("nu.pattern.OpenCV").getMethod("loadLocally").invoke(null);
            vision = (BoardVision) Class.forName("snow.rebuild.OpenCvBoardVision")
                    .getDeclaredConstructor().newInstance();
        }
        String shapes = Files.readString(Path.of("assets/shapes.txt"));
        if (dynamic) shapes = shapes.replaceAll("(?m)^L .*\\R?", "");
        try (InputStream input = new ByteArrayInputStream(shapes.getBytes(StandardCharsets.US_ASCII))) {
            reader = new Recognizer(input,vision);
        }
        try (var files = Files.list(Path.of(args[0]))) {
            for (Path path : files.sorted().toList()) {
                if (!path.toString().endsWith(".jpg")) continue;
                String key = path.getFileName().toString().substring(0,8);
                try {
                    BufferedImage im = ImageIO.read(path.toFile());
                    if (args.length > 1 && !args[1].startsWith("--")) {
                        int h = Integer.parseInt(args[1]);
                        int w = Math.round(h*(float)im.getWidth()/im.getHeight());
                        BufferedImage scaled = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                        var graphics = scaled.createGraphics();
                        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(im,0,0,w,h,null);
                        graphics.dispose();
                        im = scaled;
                    }
                    int w = im.getWidth(), h = im.getHeight();
                    Solver.Board board = reader.read(im.getRGB(0,0,w,h,null,0,w), w, h);
                    StringBuilder tiles = new StringBuilder();
                    for (Solver.Tile tile : board.tiles()) {
                        tiles.append(tile.material());
                        if (tile.chest()) tiles.append('+');
                        if (tile.yield() == 0) tiles.append('x');
                        if (tile.yield() == 4) tiles.append('^');
                    }
                    StringBuilder plan = new StringBuilder();
                    for (Solver.Plan task : board.plans()) plan.append(Arrays.toString(task.missing()));
                    Solver.Result result = Solver.solve(board);
                    if (!result.steps().isEmpty() && result.steps().get(0).mode()!=Solver.Mode.MOVE) {
                        throw new IllegalStateException("Route starts without a move");
                    }
                    System.out.printf("%s @%s %s %s %s +%s %s%n", key, board.position(), tiles,
                            Arrays.toString(board.cards()), plan, result.points(), result.steps().stream()
                                    .map(x -> x.card()+x.mode().name().substring(0,1)).toList());
                } catch (RuntimeException e) {
                    System.out.println(key+" ERROR "+e.getMessage());
                }
            }
        }
    }
}
