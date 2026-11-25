package net.syncmaterial.syncmaterial.gametest.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test-only timeline markers used by the external CI demo recorder. */
final class DemoStepLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("syncmaterial-demo");

    private DemoStepLogger() {}

    static void mark(String stepId) {
        LOGGER.info("SYNCMATERIAL_DEMO_STEP {} {}", stepId, System.currentTimeMillis());
    }
}
