package dev.jolt.adapter.maven;

import org.apache.maven.settings.*;
import org.apache.maven.settings.building.*;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.util.repository.*;

import java.nio.file.Path;

/** Loads {@code settings.xml} and applies mirrors, proxies, and server auth to an Aether session. */
public final class SettingsLoader {

    private SettingsLoader() {}

    /**
     * Load effective settings from {@code ~/.m2/settings.xml}.
     * Returns an empty {@link Settings} if the file does not exist or cannot be parsed.
     */
    public static Settings loadUserSettings() {
        return load(null, null);
    }

    /**
     * Load effective settings, optionally overriding default paths.
     * Pass {@code null} to use the default location for each argument.
     */
    public static Settings load(Path globalSettingsFile, Path userSettingsFile) {
        var factory = new DefaultSettingsBuilderFactory();
        var builder = factory.newInstance();

        var request = new DefaultSettingsBuildingRequest();
        request.setSystemProperties(System.getProperties());

        if (userSettingsFile != null) {
            request.setUserSettingsFile(userSettingsFile.toFile());
        } else {
            var def = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
            if (def.toFile().exists()) request.setUserSettingsFile(def.toFile());
        }
        if (globalSettingsFile != null) {
            request.setGlobalSettingsFile(globalSettingsFile.toFile());
        }

        try {
            return builder.build(request).getEffectiveSettings();
        } catch (SettingsBuildingException e) {
            return new Settings();
        }
    }

    /**
     * Apply mirrors, proxies, and server authentication from {@code settings} to {@code session}.
     * A no-op when {@code settings} is {@code null}.
     */
    public static void applyToSession(DefaultRepositorySystemSession session, Settings settings) {
        if (settings == null) return;

        // ── Mirrors ──────────────────────────────────────────────────────────
        var mirrorSelector = new DefaultMirrorSelector();
        for (Mirror mirror : settings.getMirrors()) {
            mirrorSelector.add(
                    mirror.getId(),
                    mirror.getUrl(),
                    mirror.getLayout(),
                    false,
                    mirror.getMirrorOf(),
                    mirror.getMirrorOfLayouts());
        }
        session.setMirrorSelector(mirrorSelector);

        // ── Proxies ──────────────────────────────────────────────────────────
        var proxySelector = new DefaultProxySelector();
        for (Proxy proxy : settings.getProxies()) {
            if (!proxy.isActive()) continue;
            org.eclipse.aether.repository.Authentication auth = null;
            if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                auth = new AuthenticationBuilder()
                        .addUsername(proxy.getUsername())
                        .addPassword(proxy.getPassword())
                        .build();
            }
            proxySelector.add(
                    new org.eclipse.aether.repository.Proxy(
                            proxy.getProtocol(), proxy.getHost(), proxy.getPort(), auth),
                    proxy.getNonProxyHosts());
        }
        session.setProxySelector(proxySelector);

        // ── Server authentication ─────────────────────────────────────────────
        var authSelector = new DefaultAuthenticationSelector();
        for (Server server : settings.getServers()) {
            var ab = new AuthenticationBuilder();
            if (server.getUsername() != null)  ab.addUsername(server.getUsername());
            if (server.getPassword() != null)  ab.addPassword(server.getPassword());
            if (server.getPrivateKey() != null) ab.addPrivateKey(server.getPrivateKey(), server.getPassphrase());
            authSelector.add(server.getId(), ab.build());
        }
        // ConservativeAuthenticationSelector: only returns auth when the server ID
        // is explicitly configured — public repos get unauthenticated access.
        session.setAuthenticationSelector(new ConservativeAuthenticationSelector(authSelector));
    }
}
