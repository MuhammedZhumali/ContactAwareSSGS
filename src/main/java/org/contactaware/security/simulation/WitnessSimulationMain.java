package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Standalone offline demo; leaves the contact-data CLI unchanged. */
public final class WitnessSimulationMain {
    private WitnessSimulationMain() {}

    public record Summary(String scenario, long served, double usefulSeconds, double groundCpuSeconds,
                          double spacecraftCpuSeconds, double cpuSeconds, double managementRadioSeconds,
                          double operationElapsedSeconds, long uplinkManagementBytes, long downlinkManagementBytes,
                          long managementBytes, long createdKeys) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: WitnessSimulationMain <new-or-empty-output-directory>");
        var out = JsonFiles.newOutput(Path.of(args[0]));
        var summaries = new ArrayList<Summary>();
        for (var sequence : HorizonWitness.Sequence.values()) for (var plan : List.of(HorizonWitness.Plan.NEXT, HorizonWitness.Plan.MULTI)) {
            var result = new SimulationEngine().run(HorizonWitness.scenario(sequence, plan));
            JsonFiles.write(out.resolve(result.scenario() + ".json"), result);
            var metrics = result.metrics();
            var costs = metrics.controlCosts();
            var summary = new Summary(result.scenario(), metrics.servedContacts(), seconds(metrics.usefulSecureTime()),
                    seconds(costs.groundCpu()), seconds(costs.spacecraftCpu()), seconds(costs.totalCpu()),
                    seconds(costs.radioOccupancy()), seconds(costs.elapsed()), costs.uplinkBytes(), costs.downlinkBytes(),
                    costs.managementBytes(), metrics.createdKeys());
            summaries.add(summary);
            System.out.printf(Locale.ROOT, "%s: served=%d useful=%.2fs CPU=%.2fs management=%dB newKeys=%d%n",
                    summary.scenario(), summary.served(), summary.usefulSeconds(), summary.cpuSeconds(), summary.managementBytes(), summary.createdKeys());
        }
        JsonFiles.write(out.resolve("summary.json"), Map.of("provenance", "SYNTHETIC_ASSUMPTION",
                "plans", "Predefined operations, not implemented policies or optimizer",
                "accounting", "Includes common PREPARE warmup; excludes earlier common provisioning, data-plane CPU and planner CPU",
                "results", summaries));
    }

    private static double seconds(Duration value) { return value.toNanos() / 1_000_000_000.0; }
}
