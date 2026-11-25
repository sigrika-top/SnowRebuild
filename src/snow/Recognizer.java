package snow.rebuild;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class Recognizer {
    private static final int WIDTH = 2700;
    private static final int HEIGHT = 1224;
    private final ArrayList<int[]> layouts = new ArrayList<>();
    private final ArrayList<Pattern> map = new ArrayList<>();
    private final ArrayList<Pattern> plan = new ArrayList<>();
    private final ArrayList<Pattern> digits = new ArrayList<>();
    private final ArrayList<Pattern> cards = new ArrayList<>();
    private final ArrayList<Pattern> yields = new ArrayList<>();
    private final BoardVision vision;

    private record Pattern(char label, boolean[] mask, int density) {}
    private record Match(int x, int y, int type, double score) {}
    private record Glyph(int left, int top, int right, int bottom, int count, boolean[] mask) {}
    static final class ObscuredFrame extends IllegalArgumentException {
        ObscuredFrame() { super("悬浮窗或其他窗口遮住了棋盘，收起后再分析。"); }
    }

    Recognizer(InputStream templates) throws IOException {
        this(templates, BoardVision::scan);
    }

    Recognizer(InputStream templates, BoardVision vision) throws IOException {
        this.vision = vision;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(templates))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts[0].equals("L")) {
                    int[] layout = new int[20];
                    for (int i = 0; i < layout.length; i++) layout[i] = Integer.parseInt(parts[i+1]);
                    layouts.add(layout);
                    continue;
                }
                boolean[] mask = new boolean[parts[2].length()];
                int density = 0;
                for (int i = 0; i < mask.length; i++) {
                    mask[i] = parts[2].charAt(i) == '1';
                    if (mask[i]) density++;
                }
                Pattern pattern = new Pattern(parts[1].charAt(0), mask, density);
                switch (parts[0]) {
                    case "M": map.add(pattern); break;
                    case "P": plan.add(pattern); break;
                    case "D": digits.add(pattern); break;
                    case "C": cards.add(pattern); break;
                    case "Y": yields.add(pattern); break;
                    default: throw new IOException("未知识别模板");
                }
            }
        }
    }

    Solver.Board read(int[] pixels, int width, int height) {
        if (width < height * 1.55 || width > height * 2.65) {
            throw new IllegalArgumentException("请打开横屏游戏，并选择共享整个屏幕。");
        }
        boolean fitToHeight = Math.abs(width/(double)height - WIDTH/(double)HEIGHT) > .055;
        try {
            return read(new Screen(pixels, width, height, fitToHeight));
        } catch (IllegalArgumentException error) {
            if (!fitToHeight || error instanceof ObscuredFrame) throw error;
            return read(new Screen(pixels, width, height, false));
        }
    }

    private Solver.Board read(Screen screen) {
        if (hasOverlay(screen)) {
            throw new ObscuredFrame();
        }
        Solver.Plan[] plans = readPlans(screen);
        int[] hand = readCards(screen);
        List<Match> detected = detectIcons(screen);
        Match[] tiles = null;
        int layout = -1;
        double best = 0;
        for (int i = 0; i < layouts.size(); i++) {
            Match[] candidate = new Match[10];
            int[] anchors = layouts.get(i);
            double quality = 0;
            for (int j = 0; j < candidate.length; j++) {
                candidate[j] = findIcon(screen, anchors[2*j], anchors[2*j+1], 36, 50, 4, map);
                if (candidate[j].score < .65) {
                    Match recovered = nearestIcon(detected, anchors[2*j], anchors[2*j+1]);
                    if (recovered != null) candidate[j] = recovered;
                }
                quality += candidate[j].score;
            }
            if (quality > best && validTiles(candidate,quality)) {
                best = quality;
                tiles = candidate;
                layout = i;
            }
        }
        Match[] visualRing = clockwiseIcons(detected);
        if (tiles == null) {
            tiles = visualRing;
            if (tiles == null) {
                throw new IllegalArgumentException("没有读全棋盘的十个资源格。请关闭遮挡棋盘的窗口后重试。");
            }
            layout = -1;
        } else if (visualRing != null) {
            for (int i=0; i<tiles.length; i++) {
                if (closeTo(tiles[i],visualRing[i],30) && tiles[i].type==visualRing[i].type
                        && tiles[i].score<visualRing[i].score) tiles[i]=visualRing[i];
            }
        }
        int position = -1;
        double current = 0;
        int shownYield = -1;
        boolean[] blocked = new boolean[10];
        boolean[] blue = new boolean[10];
        for (int i = 0; i < 10; i++) {
            Match tile = tiles[i];
            if (tile.score < .38) throw new IllegalArgumentException("有资源格没看清，等画面稳定后重试。");
            blocked[i] = redSlash(screen, tile.x, tile.y);
            blue[i] = blueMarker(screen, tile.x, tile.y);
            Glyph g = glyph(screen, tile.x-16, tile.y+33, tile.x+17, tile.y+77, 115, false);
            double beacon = beacon(screen, tile.x, tile.y);
            double quality = beacon;
            int digitValue = -1;
            if (g != null && g.count > 85 && g.count < 490 && g.bottom-g.top >= 18 && g.bottom-g.top <= 36
                    && g.top >= tile.y + 34) {
                Pattern digit = closest(g.mask, yields);
                if (digit != null && similarity(g.mask, digit) > .63) {
                    digitValue = digit.label-'0';
                    quality += 27;
                }
            }
            if (quality > current) {
                current = quality;
                position = i;
                shownYield = digitValue;
            }
        }
        if (position < 0 || current < 12 || screen.selectedCard && shownYield < 0) {
            if (position < 0 || current < 12 || !screen.selectedCard) {
                throw new IllegalArgumentException("读不到当前位置。取消选牌，等光柱稳定后重试。");
            }
            shownYield = readYield(screen,tiles[position]);
            if (shownYield < 0) throw new IllegalArgumentException("请取消选牌，让当前位置的光柱完整露出。");
        }
        if (shownYield < 0 && (layout == -1 || layout >= 6)) shownYield = readYield(screen,tiles[position]);
        int base = layout < 4 ? 2 : layout < 6 ? 3 : 5;
        boolean anyBlocked = false;
        for (boolean locked : blocked) anyBlocked |= locked;
        if (layout == -1 && shownYield <= 0) throw new IllegalArgumentException("新棋盘的格子产量读不清。");
        if (layout >= 0 && layout < 4 && anyBlocked) base += 2;
        if (shownYield > 0 && (layout == -1 || layout < 4 && !anyBlocked || layout >= 6 && anyBlocked)) base = shownYield;
        if (layout >= 6 && anyBlocked && shownYield <= 0) {
            throw new IllegalArgumentException("当前位置被封锁，无法确认本回合的产量。");
        }
        Solver.Tile[] board = new Solver.Tile[10];
        for (int i = 0; i < board.length; i++) {
            boolean bonus = layout == 0 && !anyBlocked && blue[i];
            board[i] = new Solver.Tile(tiles[i].type, blocked[i] ? 0 : base * (bonus ? 2 : 1),
                    layout == 4 && blue[i]);
        }
        return new Solver.Board(board, position, hand, plans);
    }

    private int readYield(Screen s, Match tile) {
        int value=-1;
        double best=.64;
        for (int threshold : new int[]{82,105,125,145}) {
            for (Glyph g : components(s,tile.x-23,tile.y+27,tile.x+24,tile.y+83,threshold)) {
                if (g.count<70 || g.bottom-g.top<18 || g.bottom-g.top>37 || g.right-g.left>29) continue;
                double offset=Math.abs((g.top+g.bottom)*.5-tile.y-50);
                if (offset>20) continue;
                Pattern p=closest(g.mask,yields);
                if (p==null) continue;
                double score=similarity(g.mask,p)-.004*offset;
                if (score>best) { best=score; value=p.label-'0'; }
            }
        }
        return value;
    }

    private boolean hasOverlay(Screen screen) {
        int[] counts = new int[2];
        for (int y=215; y<=765; y+=10) {
            for (int x=690; x<=1870; x+=10) {
                int rgb=screen.at(x,y);
                for (int i=0; i<counts.length; i++) {
                    int red=i==0 ? 26 : 37, green=i==0 ? 48 : 61, blue=i==0 ? 60 : 73;
                    if (Math.abs(r(rgb)-red)<=12 && Math.abs(g(rgb)-green)<=12
                            && Math.abs(b(rgb)-blue)<=12) counts[i]++;
                }
            }
        }
        return Math.max(counts[0],counts[1])>470;
    }

    private List<Match> detectIcons(Screen screen) {
        int x0=680, y0=244, width=415, height=175, scale=3;
        byte[] mask=new byte[width*height];
        for (int y=0; y<height; y++) {
            for (int x=0; x<width; x++) {
                if (screen.grey(x0+x*scale,y0+y*scale)<122) mask[y*width+x]=(byte)255;
            }
        }
        List<Match> candidates=new ArrayList<>();
        for (BoardVision.Region region : vision.regions(mask,width,height)) {
            int x=x0+(region.x()*2+region.width())*scale/2;
            int y=y0+(region.y()*2+region.height())*scale/2;
            Match match=findIcon(screen,x,y,12,12,3,map);
            if (match.score<.62 || !hasHalo(screen,match)) continue;
            candidates.add(match);
        }
        candidates.sort(Comparator.comparingDouble(Match::score).reversed());
        List<Match> filtered=new ArrayList<>();
        for (Match m : candidates) {
            if (filtered.stream().noneMatch(n -> closeTo(m,n,62))) filtered.add(m);
        }
        return filtered;
    }

    private static boolean hasHalo(Screen s, Match m) {
        int bright=0;
        for (int i=0; i<12; i++) {
            double angle=i*Math.PI/6;
            if (s.grey(m.x+(int)(Math.cos(angle)*33),m.y+(int)(Math.sin(angle)*33))>190) bright++;
        }
        return bright>=8;
    }

    private static Match nearestIcon(List<Match> candidates, int x, int y) {
        Match best=null;
        double score=.46;
        for (Match m : candidates) {
            if (Math.abs(m.x-x)>104 || Math.abs(m.y-y)>72) continue;
            double quality=m.score-.0025*Math.hypot(m.x-x,m.y-y);
            if (quality>score) { score=quality; best=m; }
        }
        return best;
    }

    private static boolean validTiles(Match[] tiles, double score) {
        if (tiles==null || score<6.5) return false;
        for (int i=0; i<tiles.length; i++) {
            if (tiles[i].score<.48) return false;
            for (int j=0; j<i; j++) if (closeTo(tiles[i],tiles[j],62)) return false;
        }
        return true;
    }

    private static boolean closeTo(Match a, Match b, int radius) {
        return (a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)<radius*radius;
    }

    private static Match[] clockwiseIcons(List<Match> candidates) {
        if (candidates.size()<10) return null;
        List<Match> ring=new ArrayList<>(candidates.subList(0,10));
        if (ring.stream().anyMatch(m -> m.score<.80)) return null;
        ring.sort(Comparator.comparingInt(Match::x).reversed());
        Match station=ring.get(0), left=ring.get(9);
        if (station.x-left.x<700 || station.x-left.x>1250) return null;
        Match[] ordered=null;
        double first=Double.POSITIVE_INFINITY, second=first;
        // The tracks run left from the station on each side of the board. An
        // inward bend can put two tiles at the same angle from the center.
        for (int split=1; split<255; split++) {
            ArrayList<Match> lower=new ArrayList<>(), upper=new ArrayList<>();
            lower.add(station);
            upper.add(station);
            for (int i=1; i<9; i++) {
                ((split & (1 << (i-1))) != 0 ? lower : upper).add(ring.get(i));
            }
            if (lower.size()<3 || upper.size()<3 || lower.get(1).y<=upper.get(1).y) continue;
            lower.add(left);
            upper.add(left);
            double route=trackLength(lower)+trackLength(upper);
            if (route<first) {
                second=first;
                first=route;
                for (int i=upper.size()-2; i>0; i--) lower.add(upper.get(i));
                ordered=lower.toArray(new Match[0]);
            } else if (route<second) {
                second=route;
            }
        }
        return first<2300 && second>first*1.025 ? ordered : null;
    }

    private static double trackLength(List<Match> path) {
        double length=0;
        for (int i=1; i<path.size(); i++) {
            Match a=path.get(i-1), b=path.get(i);
            double distance=Math.hypot((a.x-b.x)*.65,a.y-b.y);
            if (distance>380) return Double.POSITIVE_INFINITY;
            length+=distance;
        }
        return length;
    }

    private Solver.Plan[] readPlans(Screen screen) {
        Solver.Plan[] goals = new Solver.Plan[3];
        int[] textY = {168, 296, 422};
        for (int row = 0; row < 3; row++) {
            int y = textY[row];
            int[] missing = new int[Solver.TYPES];
            for (int col = 0; col < 2; col++) {
                int x = col == 0 ? 2264 : 2454;
                int iconX = col == 0 ? 2223 : 2414;
                Match icon = findIcon(screen, iconX, y+8, 10, 11, 2, plan);
                if (col == 0 && row != 0 && icon.score < .52) continue;
                int amount = readAmount(screen, x, y, row == 0 ? 250 : 50);
                if (amount == 0) continue;
                if (amount < 0 || icon.score < .44 || icon.type < 0 || icon.type >= Solver.TYPES) {
                    throw new IllegalArgumentException("重建计划有一项读不清，请让右上角完整露出。");
                }
                missing[icon.type] = amount;
            }
            goals[row] = new Solver.Plan(row == 0 ? 4 : 1, missing);
        }
        return goals;
    }

    private int readAmount(Screen screen, int x, int y, int maxTarget) {
        double best = 0;
        int amount = -1;
        for (int threshold : new int[]{143, 149, 156, 162, 168, 174, 180}) {
            List<Glyph> parts = components(screen, x, y-15, x+105, y+14, threshold);
            for (int k=0; k<parts.size(); k++) {
                Glyph slash = parts.get(k);
                if (slash.left < x+25 || slash.left > x+65 || slash.right-slash.left > 15) continue;
                double sl = digitScore(slash, '/');
                if (sl < .62) continue;
                Numeral need = numeral(parts.subList(k+1,parts.size()));
                if (need == null || need.value == 0 || need.value > maxTarget) continue;
                for (int foreground : new int[]{116, 125, 134, 144}) {
                    List<Glyph> prefix = components(screen, x, y-15, slash.left-2, y+14, foreground);
                    Numeral have = numeral(prefix);
                    if (have == null || have.value > need.value) continue;
                    double quality = (sl + 2*have.score + 2*need.score)/5;
                    if (quality > best) {
                        best = quality;
                        amount = need.value - have.value;
                    }
                }
            }
        }
        return best >= .72 ? amount : -1;
    }

    private record Numeral(int value, double score) {}

    private Numeral numeral(List<Glyph> parts) {
        if (parts.isEmpty() || parts.size() > 3) return null;
        int value = 0;
        double score = 0;
        for (Glyph glyph : parts) {
            if (glyph.right-glyph.left > 22) return null;
            Pattern best = null;
            double quality = 0;
            for (Pattern p : digits) {
                if (p.label == '/') continue;
                double next = similarity(glyph.mask, p);
                if (next > quality) { quality = next; best = p; }
            }
            if (best == null || quality < .60 || value == 0 && score > 0) return null;
            value = value*10 + best.label-'0';
            score += quality;
        }
        return new Numeral(value, score/parts.size());
    }

    private double digitScore(Glyph glyph, char label) {
        double score = 0;
        for (Pattern p : digits) {
            if (p.label == label) score = Math.max(score,similarity(glyph.mask,p));
        }
        return score;
    }

    private int[] readCards(Screen screen) {
        int[] hand = new int[8];
        int count = 0;
        for (int i = 0; i < 8; i++) {
            int x = 144+167*i;
            if (screen.grey(x-39,1068)>163 && screen.grey(x+40,1068)>163
                    && screen.grey(x-34,1007)>154 && screen.grey(x+35,1007)>154) {
                Glyph selected = cardDigit(screen,x);
                if (selected != null) {
                    Pattern digit=closest(selected.mask,cards);
                    if (digit != null && similarity(selected.mask,digit)>=.63) {
                        hand[count++]=digit.label-'0';
                        screen.selectedCard=true;
                        continue;
                    }
                }
            }
            int dark = 0;
            for (int dx = -48; dx <= 48; dx += 16) {
                for (int dy = 0; dy <= 42; dy += 7) {
                    if (screen.grey(x+dx, 976+dy) < 100) dark++;
                }
            }
            if (dark < 32) break;
            Glyph g = glyph(screen, x-26, 1001, x+27, 1081, 179, true);
            if (g == null || g.count < 145 || g.bottom-g.top < 44 || g.bottom-g.top > 78) break;
            Pattern p = closest(g.mask, cards);
            if (p == null || similarity(g.mask, p) < .55) {
                throw new IllegalArgumentException("手牌点数读不清。请先取消选中，再分析。");
            }
            hand[count++] = p.label-'0';
        }
        return Arrays.copyOf(hand, count);
    }

    private static Glyph cardDigit(Screen screen, int x) {
        int w=53, h=88, left=w, top=h, right=0, bottom=0, count=0;
        boolean[] dark=new boolean[w*h];
        for (int y=0; y<h; y++) {
            for (int i=0; i<w; i++) {
                int rgb=screen.at(x-26+i,974+y);
                if (b(rgb)-r(rgb)<32 || b(rgb)-g(rgb)<35 || r(rgb)>103) continue;
                dark[y*w+i]=true;
                left=Math.min(left,i); right=Math.max(right,i+1);
                top=Math.min(top,y); bottom=Math.max(bottom,y+1);
                count++;
            }
        }
        return count<90 || bottom-top<43 ? null
                : new Glyph(left,top,right,bottom,count,sample(dark,w,left,top,right,bottom));
    }

    private static Match findIcon(Screen screen, int x, int y, int rx, int ry, int step, List<Pattern> patterns) {
        Match best = new Match(x,y,-1,0);
        boolean[] sample = new boolean[289];
        for (int cy = y-ry; cy <= y+ry; cy += step) {
            for (int cx = x-rx; cx <= x+rx; cx += step) {
                if (screen.grey(cx, cy) > 175 && screen.grey(cx-12, cy) > 175 && screen.grey(cx+12, cy) > 175) continue;
                int n = 0;
                int density = 0;
                for (int dy = -8; dy <= 8; dy++) {
                    for (int dx = -8; dx <= 8; dx++) {
                        boolean dark = screen.grey(cx+dx*3, cy+dy*3) < 120;
                        sample[n++] = dark;
                        if (dark) density++;
                    }
                }
                if (density < 31 || density > 244) continue;
                double shift = 0.00065 * (Math.abs(cx-x) + Math.abs(cy-y));
                for (Pattern pattern : patterns) {
                    if (pattern.density < 25) continue;
                    double score = similarity(sample, density, pattern) - shift;
                    if (score > best.score) best = new Match(cx, cy, pattern.label-'0', score);
                }
            }
        }
        return best;
    }

    private static Pattern closest(boolean[] mask, List<Pattern> patterns) {
        int count = 0;
        for (boolean b : mask) if (b) count++;
        Pattern best = null;
        double score = 0;
        for (Pattern p : patterns) {
            double next = similarity(mask, count, p);
            if (next > score) {
                best = p;
                score = next;
            }
        }
        return best;
    }

    private static double similarity(boolean[] sample, Pattern pattern) {
        int density = 0;
        for (boolean b : sample) if (b) density++;
        return similarity(sample, density, pattern);
    }

    private static double similarity(boolean[] sample, int density, Pattern pattern) {
        int intersection = 0;
        for (int i = 0; i < sample.length; i++) if (sample[i] && pattern.mask[i]) intersection++;
        return 2.0 * intersection / Math.max(1, density + pattern.density);
    }

    private static boolean redSlash(Screen s, int x, int y) {
        int red = 0;
        for (int dy=-24; dy<=24; dy+=3) {
            for (int dx=-27; dx<=27; dx+=3) {
                int p = s.at(x+dx,y+dy);
                if (r(p) > 100 && r(p)-g(p) > 37 && r(p)-b(p)>43) red++;
            }
        }
        return red > 14;
    }

    private static boolean blueMarker(Screen s, int x, int y) {
        int blue = 0;
        for (int dy=-15; dy<=15; dy+=3) {
            for (int dx=31; dx<=80; dx+=3) {
                int p = s.at(x+dx,y+dy);
                if (b(p)-r(p)>48 && b(p)-g(p)>12 && b(p)>125) blue++;
            }
        }
        return blue > 32;
    }

    private static double beacon(Screen s, int x, int y) {
        double best = 0;
        for (int dx = -22; dx <= 22; dx += 3) {
            double score = 0;
            for (int up = 67; up <= 162; up += 5) {
                int p = s.at(x+dx, y-up);
                int left = s.at(x+dx-23, y-up);
                int right = s.at(x+dx+23, y-up);
                if (b(p)-r(p) > 14 && b(p) > 167) {
                    int contrast = b(p) - Math.max(b(left), b(right));
                    score += Math.max(0, Math.min(45, contrast));
                }
            }
            best = Math.max(best, score / 17);
        }
        return best;
    }

    private static Glyph glyph(Screen s, int x0, int y0, int x1, int y1, int t, boolean light) {
        int width = x1-x0;
        int height = y1-y0;
        boolean[] content = new boolean[width*height];
        int count = 0, left = width, top = height, right = 0, bottom = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((s.grey(x+x0,y+y0) > t) == light) {
                    content[y*width+x] = true;
                    count++;
                    left = Math.min(left,x); right = Math.max(right,x+1);
                    top = Math.min(top,y); bottom = Math.max(bottom,y+1);
                }
            }
        }
        return count < 12 ? null : new Glyph(x0+left,y0+top,x0+right,y0+bottom,count,
                sample(content,width,left,top,right,bottom));
    }

    private static List<Glyph> components(Screen s, int x0, int y0, int x1, int y1, int threshold) {
        int w = x1-x0, h = y1-y0;
        boolean[] pixels = new boolean[w*h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) pixels[y*w+x] = s.grey(x0+x,y0+y) <= threshold;
        }
        ArrayList<Glyph> result = new ArrayList<>();
        int[] queue = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            if (!pixels[i]) continue;
            int size = 1, left=w, right=0, top=h, bottom=0;
            pixels[i] = false;
            queue[0] = i;
            for (int q = 0; q < size; q++) {
                int cx=queue[q]%w, cy=queue[q]/w;
                left=Math.min(left,cx); right=Math.max(right,cx+1);
                top=Math.min(top,cy); bottom=Math.max(bottom,cy+1);
                for (int dy=-1; dy<=1; dy++) {
                    for (int dx=-1; dx<=1; dx++) {
                        int x=cx+dx, y=cy+dy, index=y*w+x;
                        if (x<0 || x>=w || y<0 || y>=h || !pixels[index]) continue;
                        pixels[index] = false;
                        queue[size++] = index;
                    }
                }
            }
            if (size >= 12 && bottom-top >= 9) {
                boolean[] component = new boolean[pixels.length];
                for (int q=0; q<size; q++) component[queue[q]] = true;
                result.add(new Glyph(left+x0,top+y0,right+x0,bottom+y0,size,
                        sample(component,w,left,top,right,bottom)));
            }
        }
        result.sort(Comparator.comparingInt(Glyph::left));
        return result;
    }

    private static boolean[] sample(boolean[] pixels, int width, int l, int t, int r, int b) {
        boolean[] output = new boolean[216];
        for (int y=0; y<18; y++) {
            for (int x=0; x<12; x++) {
                output[y*12+x] = pixels[(t+(2*y+1)*(b-t)/36)*width+l+(2*x+1)*(r-l)/24];
            }
        }
        return output;
    }

    private static int r(int p) { return p >> 16 & 255; }
    private static int g(int p) { return p >> 8 & 255; }
    private static int b(int p) { return p & 255; }

    private static final class Screen {
        boolean selectedCard;
        private final int[] colors = new int[WIDTH*HEIGHT];
        private final byte[] greys = new byte[WIDTH*HEIGHT];

        Screen(int[] data, int width, int height, boolean fitToHeight) {
            for (int y=0; y<HEIGHT; y++) {
                for (int x=0; x<WIDTH; x++) {
                    int sourceX = x*width/WIDTH;
                    if (fitToHeight) {
                        int offset = width-WIDTH*height/HEIGHT;
                        int anchor = y>=830 ? 0 : x>=2080 ? offset : offset/2;
                        sourceX = Math.max(0,Math.min(width-1, x*height/HEIGHT+anchor));
                    }
                    int p = data[(y*height/HEIGHT)*width+sourceX];
                    int idx=y*WIDTH+x;
                    colors[idx]=p;
                    greys[idx]=(byte)((299*r(p)+587*g(p)+114*b(p)+500)/1000);
                }
            }
        }

        int at(int x,int y) { return colors[y*WIDTH+x]; }
        int grey(int x,int y) { return greys[y*WIDTH+x]&255; }
    }
}
