package dev.agentkit.host.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Signs the caller assertion every connector call carries: a JWT, ES256, lasting {@link #LIFETIME}, saying which
 * organization, which person (or agent, or the host itself), which agent at which version, and which conversation and
 * turn the call is made for. The connector checks it against the keys the host publishes ({@link #jwks()}) and takes
 * who is calling from it — not from an argument a model could have written.
 *
 * <p>The key is the host's to keep: from {@code AGENTKIT_HOST_SIGNING_KEY} (a private JWK) when several instances must
 * share it, or from a file in the host's data directory, made on first start.
 */
public final class CallerSigner {

    /** How long an assertion lasts: it covers one call. */
    public static final Duration LIFETIME = Duration.ofSeconds(60);

    private final String issuer;
    private final ECKey key;

    /**
     * Who a call is for.
     *
     * @param subject a person's email, {@code agent:<name>} for an agent acting on its own, or {@code host}
     * @param email   the person's email; null for an agent or the host
     */
    public record Caller(String org, String subject, String email, String agent, String agentVersion,
                         String conversation, String turn) {
        public Caller {
            Objects.requireNonNull(org, "org");
            Objects.requireNonNull(subject, "subject");
        }

        /** The host looking something up for itself: a person's directory record, a subject of deferred work. */
        public static Caller host(String org) {
            return new Caller(org, "host", null, null, null, null, null);
        }
    }

    private CallerSigner(String issuer, ECKey key) {
        this.issuer = Objects.requireNonNull(issuer, "issuer").replaceAll("/+$", "");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** A signer with a new key, for as long as the process runs. */
    public static CallerSigner generate(String issuer) {
        try {
            return new CallerSigner(issuer, newKey());
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not make a signing key", e);
        }
    }

    /** A signer with the key {@code privateJwk} holds. */
    public static CallerSigner fromJwk(String issuer, String privateJwk) {
        try {
            ECKey key = ECKey.parse(privateJwk);
            if (!key.isPrivate()) {
                throw new IllegalArgumentException("The signing key must include its private part");
            }
            return new CallerSigner(issuer, key);
        } catch (ParseException e) {
            throw new IllegalArgumentException("The signing key is not an EC JSON Web Key", e);
        }
    }

    /** A signer with the key kept in {@code file}, made there (readable by its owner only) if there is none yet. */
    public static CallerSigner inFile(String issuer, Path file) {
        try {
            if (Files.exists(file)) {
                return fromJwk(issuer, Files.readString(file, StandardCharsets.UTF_8));
            }
            ECKey key = newKey();
            Files.createDirectories(file.getParent());
            Files.writeString(file, key.toJSONString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) {
                // A file system without POSIX permissions keeps its own.
            }
            return new CallerSigner(issuer, key);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep the signing key in " + file, e);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not make a signing key", e);
        }
    }

    public String issuer() {
        return issuer;
    }

    /** The public half, as a JSON Web Key Set: what the host publishes at {@code /.well-known/jwks.json}. */
    public String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    /** The assertion for one call to the connector {@code audience}, for {@code caller}. */
    public String sign(Caller caller, String audience) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience)
                .subject(caller.subject()).claim("org", caller.org())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(LIFETIME)))
                .jwtID(UUID.randomUUID().toString());
        putIfPresent(claims, "email", caller.email());
        putIfPresent(claims, "agent", caller.agent());
        putIfPresent(claims, "agent_version", caller.agentVersion());
        putIfPresent(claims, "conversation", caller.conversation());
        putIfPresent(claims, "turn", caller.turn());
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a caller assertion", e);
        }
    }

    private static void putIfPresent(JWTClaimsSet.Builder claims, String name, String value) {
        if (value != null && !value.isBlank()) {
            claims.claim(name, value);
        }
    }

    private static ECKey newKey() throws JOSEException {
        return new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyID(UUID.randomUUID().toString()).generate();
    }
}
