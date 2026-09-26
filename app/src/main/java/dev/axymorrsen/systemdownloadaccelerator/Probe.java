package dev.axymorrsen.systemdownloadaccelerator;

final class Probe {
    private Probe() {}

    static int countAvailable(ClassLoader loader, String... classNames) {
        int count = 0;
        for (String className : classNames) {
            try {
                loader.loadClass(className);
                count++;
            } catch (Throwable ignored) {
                // OEM forks are expected to move or replace private classes.
            }
        }
        return count;
    }
}
