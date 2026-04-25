package com.openbeken.broker;

/**
 * Standalone entry point for the embedded MQTT broker.
 * Run separately so devices can connect before the CLI starts.
 *
 * Usage:
 *   ./gradlew :mqtt:broker
 *   # or with custom port:
 *   ./gradlew :mqtt:broker --args="1883"
 */
public class BrokerMain {

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 1883;

        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════╗");
        System.out.println("║       OpenBeken MQTT Broker (Moquette)                ║");
        System.out.println("║  Embedded broker for OpenBeken device communication   ║");
        System.out.println("╚═══════════════════════════════════════════════════════╝");
        System.out.println();

        EmbeddedBroker broker = new EmbeddedBroker(port);
        broker.start();

        if (!broker.isRunning()) {
            System.err.println("✗ Broker failed to start. Exiting.");
            System.exit(1);
        }

        System.out.println();
        System.out.println("  Broker is running. OpenBeken devices can connect to:");
        System.out.printf("    mqtt://<this-machine-ip>:%d\n", port);
        System.out.println();
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();

        // Keep running until killed
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n→ Shutting down broker...");
            broker.stop();
            System.out.println("✓ Broker stopped.");
        }));

        // Block forever
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
