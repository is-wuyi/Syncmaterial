package net.syncmaterial.syncmaterial.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientTestReportingAdapter implements LanguageAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("syncmaterial-test-report");

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        T entrypoint = LanguageAdapter.getDefault().create(mod, value, type);
        if (!type.getName().equals("net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest")) {
            return entrypoint;
        }
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
            boolean runTest = method.getName().equals("runTest");
            if (runTest) LOGGER.info("SYNCMATERIAL_CLIENT_TEST_START {} {}", mod.getMetadata().getId(), value);
            try {
                Object result = method.invoke(entrypoint, arguments);
                if (runTest) LOGGER.info("SYNCMATERIAL_CLIENT_TEST_PASS {} {}", mod.getMetadata().getId(), value);
                return result;
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (runTest) LOGGER.error("SYNCMATERIAL_CLIENT_TEST_FAIL {} {}", mod.getMetadata().getId(), value, cause);
                throw cause;
            }
        }));
    }
}
