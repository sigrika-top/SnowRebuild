package snow.rebuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Solver {
    static final String[] MATERIAL = {"矿石", "木材", "零件", "能源", "金属"};
    static final int TYPES = MATERIAL.length;
    record Tile(int material, int yield, boolean chest) {}
    record Plan(int points, int[] missing) {}
    record Board(Tile[] tiles, int position, int[] cards, Plan[] plans) {}
    enum Mode { MOVE, COLLECT, COMBO }
    record Step(int card, Mode mode, int position) {}
    record Result(List<Step> steps, int points, double progress, int chests) {}

    static Result solve(Board board) {
        Solver solver = new Solver(board);
        int[] remaining = new int[6];
        for (int card : board.cards) remaining[card]++;
        solver.search(new State(remaining, board.position, -1,
                new int[TYPES], 0), new ArrayList<>());
        return solver.best;
    }

    static String explainNoProgress(Board board) {
        boolean[] needed = new boolean[TYPES];
        for (Plan plan : board.plans) {
            for (int i=0; i<TYPES; i++) needed[i] |= plan.missing[i]>0;
        }
        String missing = materialNames(needed);
        if (missing.isEmpty()) return "当前计划的材料已经集齐，可以结束回合。";
        if (board.cards.length < 2) return "只剩一张牌，前进后没有牌可以收集。可以结束回合。";
        boolean[] available = new boolean[TYPES];
        boolean blocked = false;
        int full = (1 << board.cards.length)-1;
        // Leave at least one card in hand to collect after moving.
        for (int subset=1; subset<full; subset++) {
            int position=board.position;
            for (int i=0; i<board.cards.length; i++) {
                if ((subset & (1 << i))!=0) position+=board.cards[i];
            }
            Tile tile=board.tiles[position % board.tiles.length];
            if (tile.yield>0) available[tile.material]=true;
            else blocked=true;
        }
        String materials = materialNames(available);
        String landings = materials.isEmpty() ? "有牌可收集的落点均已封锁。"
                : "留牌采集时只能到达" + materials + "格" + (blocked ? "或封锁格。" : "。");
        return "计划还缺" + missing + "。\n" + landings + "\n可以结束回合。";
    }

    private static String materialNames(boolean[] included) {
        List<String> names = new ArrayList<>();
        for (int i=0; i<TYPES; i++) if (included[i]) names.add(MATERIAL[i]);
        return String.join("、", names);
    }

    private record State(int[] cards, int position, int collectFrom, int[] goods, int chests) {}
    private record Key(int cards, int position, int collectFrom, int chests,
                       int ore, int wood, int part, int energy, int metal) {}

    private final Board board;
    private final int[] limits = new int[TYPES];
    private final Map<Key, Integer> visited = new HashMap<>();
    private Result best = new Result(Collections.emptyList(), 0, 0, 0);

    private Solver(Board board) {
        this.board = board;
        for (Plan plan : board.plans) {
            for (int i = 0; i < TYPES; i++) limits[i] = Math.max(limits[i], plan.missing[i]);
        }
    }

    private void search(State state, ArrayList<Step> steps) {
        int[] g = state.goods;
        int packed = 0;
        for (int c : state.cards) packed = packed * 9 + c;
        Key key = new Key(packed, state.position, state.collectFrom, state.chests,
                g[0], g[1], g[2], g[3], g[4]);
        Integer previous = visited.putIfAbsent(key, steps.size());
        if (previous != null && previous <= steps.size()) return;
        visited.put(key, steps.size());
        consider(state, steps);

        for (int card = 1; card <= 5; card++) {
            if (state.cards[card] == 0) continue;
            int[] cards = state.cards.clone();
            cards[card]--;
            Tile tile = board.tiles[state.position];
            boolean combo = state.collectFrom > 0;
            if (tile.yield > 0 && (state.collectFrom == 0 || state.collectFrom + 1 == card && combo)) {
                int[] goods = g.clone();
                if (combo) {
                    for (int i = 0; i < TYPES; i++) goods[i] = Math.min(limits[i], goods[i] + 2);
                }
                goods[tile.material] = Math.min(limits[tile.material], goods[tile.material] + tile.yield * card);
                boolean opensCombo = card < 5 && cards[card+1] > 0;
                if (!Arrays.equals(goods, g) || opensCombo) {
                    visit(new State(cards, state.position, card, goods, state.chests),
                            new Step(card, combo ? Mode.COMBO : Mode.COLLECT, state.position), steps);
                }
            }
            int position = state.position;
            int chests = state.chests;
            for (int i = 0; i < card; i++) {
                position = (position + 1) % board.tiles.length;
                if (board.tiles[position].chest) chests |= 1 << position;
            }
            visit(new State(cards, position, 0, g, chests), new Step(card, Mode.MOVE, position), steps);
        }
    }

    private void visit(State state, Step step, ArrayList<Step> steps) {
        steps.add(step);
        search(state, steps);
        steps.remove(steps.size() - 1);
    }

    private void consider(State state, List<Step> steps) {
        int score = 0;
        double progress = 0;
        for (Plan plan : board.plans) {
            int missing = 0;
            int collected = 0;
            for (int i = 0; i < TYPES; i++) {
                missing += plan.missing[i];
                collected += Math.min(plan.missing[i], state.goods[i]);
            }
            if (missing == 0) continue;
            if (collected == missing) score += plan.points;
            else progress += plan.points * (double) collected / missing;
        }
        int chests = Integer.bitCount(state.chests);
        if (score > best.points
                || score == best.points && progress > best.progress + 0.00001
                || score == best.points && Math.abs(progress - best.progress) < 0.00001
                && (chests > best.chests || chests == best.chests && steps.size() < best.steps.size())) {
            best = new Result(Collections.unmodifiableList(new ArrayList<>(steps)), score, progress, chests);
        }
    }
}
