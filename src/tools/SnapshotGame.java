package tools;

import ai.RandomBiasedAI;
import ai.abstraction.HeavyRush;
import ai.abstraction.LightRush;
import ai.abstraction.RangedRush;
import ai.abstraction.WorkerRush;
import ai.core.AI;
import gui.PhysicalGameStatePanel;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Runs a headless MicroRTS game between two scripted AIs and writes a PNG
 * snapshot of the board at a target tick.
 *
 * Usage:
 *   java -cp "lib/*:bin" tools.SnapshotGame <mapPath> <tick> <outPng> [<ai1>] [<ai2>] [<width>] [<height>]
 *
 * Defaults: ai1=WorkerRush, ai2=LightRush, width=900, height=900.
 */
public class SnapshotGame {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: SnapshotGame <mapPath> <tick> <outPng> [ai1] [ai2] [w] [h]");
            System.exit(1);
        }
        String mapPath = args[0];
        int targetTick = Integer.parseInt(args[1]);
        String outPng = args[2];
        String ai1Name = args.length > 3 ? args[3] : "WorkerRush";
        String ai2Name = args.length > 4 ? args[4] : "LightRush";
        int width = args.length > 5 ? Integer.parseInt(args[5]) : 900;
        int height = args.length > 6 ? Integer.parseInt(args[6]) : 900;

        UnitTypeTable utt = new UnitTypeTable();
        PhysicalGameState pgs = PhysicalGameState.load(mapPath, utt);
        GameState gs = new GameState(pgs, utt);

        AI ai1 = makeAI(ai1Name, utt);
        AI ai2 = makeAI(ai2Name, utt);
        ai1.reset();
        ai2.reset();

        boolean gameover = false;
        int safetyCap = targetTick + 10;
        while (!gameover && gs.getTime() < targetTick && gs.getTime() < safetyCap) {
            PlayerAction pa1 = ai1.getAction(0, gs);
            PlayerAction pa2 = ai2.getAction(1, gs);
            gs.issueSafe(pa1);
            gs.issueSafe(pa2);
            gameover = gs.cycle();
        }

        // Instantiate panel to initialize color statics (PLAYER0_OUTLINE etc.).
        PhysicalGameStatePanel panel = new PhysicalGameStatePanel(gs);
        panel.setColorScheme(PhysicalGameStatePanel.COLORSCHEME_WHITE);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, width, height);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, Math.max(10, width / 80)));

        PhysicalGameStatePanel.draw(
            g2d, null, width, height, gs, null,
            PhysicalGameStatePanel.COLORSCHEME_WHITE, true, -1, null);

        g2d.dispose();
        File out = new File(outPng);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", out);
        System.out.println("Wrote " + outPng + " (tick=" + gs.getTime() + ", units=" + pgs.getUnits().size() + ")");
    }

    private static AI makeAI(String name, UnitTypeTable utt) {
        switch (name) {
            case "RandomBiasedAI":  return new RandomBiasedAI();
            case "WorkerRush":      return new WorkerRush(utt);
            case "LightRush":       return new LightRush(utt);
            case "HeavyRush":       return new HeavyRush(utt);
            case "RangedRush":      return new RangedRush(utt);
            default: throw new IllegalArgumentException("Unknown AI: " + name);
        }
    }
}
