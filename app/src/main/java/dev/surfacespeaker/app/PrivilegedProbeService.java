package dev.surfacespeaker.app;

/** Instantiated by the Shizuku server as UID 2000 (ADB) or UID 0 (Sui). */
public final class PrivilegedProbeService extends IProbeService.Stub {
    public PrivilegedProbeService() { }
    @Override public String readHardware() { return ProbeCommands.execute(false); }
    @Override public void destroy() { System.exit(0); }
}
