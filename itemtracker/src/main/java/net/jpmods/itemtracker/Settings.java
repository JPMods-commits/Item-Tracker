package net.jpmods.itemtracker;

/** Only tracker preferences are saved. Counts and opponent history are session-only. */
public final class Settings {
    public Potion potion = new Potion();
    public DiaSMP diasmp = new DiaSMP();

    public static class Hud {
        public boolean enabled = true;
        public boolean resetOnEnable = true;
        public boolean showHud = true;
        public boolean showZeroCounts;
        public boolean showPlayerNames;
        public double hudX = 2;
        public double hudY;
        public double hudScale = 1;
        public int maxRows = 10;
        public boolean background = true;
        public boolean textShadow = true;
        public int textColor = 0xFFFFFF;

        void normalize() {
            hudX = clamp(hudX, 0, 100, 2);
            hudY = clamp(hudY, 0, 100, 30);
            hudScale = clamp(hudScale, 0.5, 3, 1);
            maxRows = Math.max(1, Math.min(30, maxRows));
            textColor &= 0xFFFFFF;
        }
    }

    public static final class Potion extends Hud {
        public double range = 64;
        public boolean countLingering = true;
        public Potion() { hudY = 30; }
        @Override void normalize() {
            super.normalize();
            range = clamp(range, 1, 64, 64);
        }
    }

    public static final class DiaSMP extends Hud {
        public double range = 64;
        public double webRange = 94;
        public boolean clientWebEstimates = true;
        public boolean unseenWebs = true;
        public int timingGrace = 2;
        public String webOpponent = "";
        public DiaSMP() { hudY = 45; }
        @Override void normalize() {
            super.normalize();
            range = clamp(range, 1, 64, 64);
            webRange = clamp(webRange, 1, 94, 94);
            timingGrace = Math.max(0, Math.min(10, timingGrace));
            if(webOpponent == null || !webOpponent.matches("[A-Za-z0-9_]{0,16}")) webOpponent = "";
        }
    }

    public void normalize() {
        if(potion == null) potion = new Potion();
        if(diasmp == null) diasmp = new DiaSMP();
        potion.normalize();
        diasmp.normalize();
    }

    private static double clamp(double v, double min, double max, double fallback) {
        return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
    }
}
