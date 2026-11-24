// The phone's stats only the shell user can read, from a Shizuku user service.
package dev.ii.tools.shell;

interface IShellStats {
    /** Shizuku's own call to end the service. */
    void destroy() = 16777114;

    /** CPU use, sensor temperatures, GPU use (JSON), every sample. */
    String sample() = 1;

    /** The busiest processes since the last call (JSON array). */
    String processes() = 2;

    /** Slow facts: battery health, storage by type, the thermal HAL (JSON). */
    String slow() = 3;
}
