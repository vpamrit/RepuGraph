package edu.vanderbilt.repugraph;


public class FlagBasedRange {
    private long lastRecv;
    private int flag;

    public FlagBasedRange() {
        lastRecv = System.currentTimeMillis();
        flag = 0;
    }

    public void recordRecv() {
        lastRecv = System.currentTimeMillis();
        flag = 0;
    }

    public void incrementFlag() {
        lastRecv = System.currentTimeMillis();
        flag++;
    }

    public int getFlag() {
        return flag;
    }

    public long timeSinceLastRecv() {
        return System.currentTimeMillis() - lastRecv;
    }
}