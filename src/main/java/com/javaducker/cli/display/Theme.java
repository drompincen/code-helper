package com.javaducker.cli.display;

/**
 * ANSI color/style constants and helpers for terminal output.
 */
public final class Theme {

    public static final String RESET  = "\033[0m";
    public static final String BOLD   = "\033[1m";
    public static final String DIM    = "\033[2m";
    public static final String GREEN  = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED    = "\033[31m";
    public static final String CYAN   = "\033[36m";
    public static final String BLUE   = "\033[34m";

    private Theme() {}

    public static String bold(String s)   { return BOLD   + s + RESET; }
    public static String dim(String s)    { return DIM    + s + RESET; }
    public static String green(String s)  { return GREEN  + s + RESET; }
    public static String yellow(String s) { return YELLOW + s + RESET; }
    public static String red(String s)    { return RED    + s + RESET; }
    public static String cyan(String s)   { return CYAN   + s + RESET; }
}
