package com.circleguard.gateway.e2e;

import com.circleguard.gateway.service.QrValidationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("e2e")
class CampusAccessE2ETest {

    private static final String QR_SECRET = "my-super-secret-test-key-32-chars-long";

    private QrValidationService validationService;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);

        validationService = new QrValidationService(redisTemplate);
        ReflectionTestUtils.setField(validationService, "qrSecret", QR_SECRET);
    }

    @Test
    void clearStudentCanEnterCampusWithGeneratedQrToken() {
        String anonymousId = UUID.randomUUID().toString();
        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn("CLEAR");

        QrValidationService.ValidationResult result = validationService.validateToken(signedTokenFor(anonymousId));

        assertTrue(result.valid());
        assertEquals("GREEN", result.status());
        assertEquals("Welcome to Campus", result.message());
    }

    @Test
    void confirmedHealthRiskIsDeniedAtTheGate() {
        String anonymousId = UUID.randomUUID().toString();
        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn("CONTAGIED");

        QrValidationService.ValidationResult result = validationService.validateToken(signedTokenFor(anonymousId));

        assertFalse(result.valid());
        assertEquals("RED", result.status());
        assertEquals("Access Denied: Health Risk Detected", result.message());
    }

    @Test
    void potentialExposureIsDeniedBeforeCampusEntry() {
        String anonymousId = UUID.randomUUID().toString();
        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn("POTENTIAL");

        QrValidationService.ValidationResult result = validationService.validateToken(signedTokenFor(anonymousId));

        assertFalse(result.valid());
        assertEquals("RED", result.status());
        assertEquals("Access Denied: Health Risk Detected", result.message());
    }

    @Test
    void unknownStatusDefaultsToGreenForNewlyEnrolledVisitor() {
        String anonymousId = UUID.randomUUID().toString();
        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn(null);

        QrValidationService.ValidationResult result = validationService.validateToken(signedTokenFor(anonymousId));

        assertTrue(result.valid());
        assertEquals("GREEN", result.status());
    }

    @Test
    void tamperedQrTokenCannotBeUsedAtGate() {
        String token = signedTokenFor(UUID.randomUUID().toString()) + "-tampered";

        QrValidationService.ValidationResult result = validationService.validateToken(token);

        assertFalse(result.valid());
        assertEquals("RED", result.status());
        assertEquals("Invalid or Expired Token", result.message());
    }

    private String signedTokenFor(String anonymousId) {
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId)
                .setIssuedAt(Date.from(Instant.now()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
