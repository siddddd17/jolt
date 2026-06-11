package dev.jolt.cli;

import picocli.CommandLine.IVersionProvider;

import java.util.Properties;

final class JoltVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws Exception {
        var props = new Properties();
        try (var is = getClass().getResourceAsStream("/jolt-version.properties")) {
            if (is != null) props.load(is);
        }
        String version = props.getProperty("version", "unknown");
        return new String[]{"jolt " + version};
    }
}
