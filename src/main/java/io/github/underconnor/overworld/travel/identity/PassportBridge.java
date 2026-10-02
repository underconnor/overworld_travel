package io.github.underconnor.overworld.travel.identity;

import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Uses the server's registered public API without packaging another plugin's classes. */
final class PassportBridge {
    private static final String SERVICE_NAME = "io.github.underconnor.passport.api.PassportIdentityService";
    private static final IdentityReader UNAVAILABLE = id -> Optional.empty();

    private PassportBridge() {}

    @FunctionalInterface
    interface IdentityReader {
        Optional<String> realName(UUID id);
    }

    static IdentityReader lookup(ServicesManager services) {
        for (Class<?> serviceType : services.getKnownServices()) {
            if (!SERVICE_NAME.equals(serviceType.getName())) {
                continue;
            }
            RegisteredServiceProvider<?> registration = services.getRegistration(serviceType);
            if (registration == null || !registration.getPlugin().isEnabled()) {
                continue;
            }
            try {
                // The implementation can be anonymous/non-public. Invoke its public interface.
                Method identity = serviceType.getMethod("identity", UUID.class);
                Object provider = registration.getProvider();
                return id -> readIdentity(identity, provider, id, Instant.now());
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return UNAVAILABLE;
            }
        }
        return UNAVAILABLE;
    }

    static Optional<String> readIdentity(Method identityMethod, Object provider, UUID id, Instant now) {
        try {
            Object result = identityMethod.invoke(provider, id);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            Object identity = optional.orElseThrow();
            Class<?> type = identity.getClass();
            Object claimedId = type.getMethod("minecraftUuid").invoke(identity);
            Object realName = type.getMethod("realName").invoke(identity);
            Object expiresAt = type.getMethod("expiresAt").invoke(identity);
            if (!id.equals(claimedId)
                    || !(realName instanceof String name) || name.isBlank()
                    || !(expiresAt instanceof Instant expiry) || !expiry.isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // An absent, incompatible, expired, or failing Passport must not expose a stale name.
            return Optional.empty();
        }
    }
}
