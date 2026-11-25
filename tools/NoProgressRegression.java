package snow.rebuild;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

final class NoProgressRegression {
    public static void main(String[] args) throws Exception {
        var image = ImageIO.read(Path.of(args[0]).toFile());
        int width = image.getWidth(), height = image.getHeight();
        var reader = new Recognizer(Files.newInputStream(Path.of("assets/shapes.txt")));
        var board = reader.read(image.getRGB(0,0,width,height,null,0,width),width,height);
        require(Arrays.equals(board.cards(),new int[]{4,4,4,4}), "Four 4-point cards");
        require(board.position()==8 && board.tiles()[8].yield()==7, "Current tile and yield");
        require(Solver.solve(board).steps().isEmpty(), "No collection contributes on round 5");
        require(Solver.explainNoProgress(board).equals(
                "计划还缺矿石、金属。\n留牌采集时只能到达能源格或封锁格。\n可以结束回合。"), "Explain blocked landings");

        Solver.Tile[] tiles = new Solver.Tile[10];
        Arrays.fill(tiles,new Solver.Tile(3,7,false));
        var combo = new Solver.Board(tiles,0,new int[]{1,4,5},
                new Solver.Plan[]{new Solver.Plan(1,new int[]{2,0,0,0,0})});
        var route = Solver.solve(combo);
        require(route.points()==1 && route.steps().size()==3
                && route.steps().get(0).mode()==Solver.Mode.MOVE
                && route.steps().get(1).mode()==Solver.Mode.COLLECT
                && route.steps().get(2).mode()==Solver.Mode.COMBO, "Unneeded collection can start a productive combo");
        System.out.println("Round 5 and COMBO regressions passed");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
