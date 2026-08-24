package com.world_dance.ms_notification_._streaming.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.KickTokenResponseDto;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.ToggleStreamStateRequestDto;
import com.world_dance.wd_lib_common.entity.KickOAuthToken;
import com.world_dance.wd_lib_common.entity.StreamSession;
import com.world_dance.wd_lib_common.entity.Timestamps;
import com.world_dance.wd_lib_common.enums.StatusStream;
import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KickApiClientServiceImpl implements KickApiClientService {

    private final WebClient.Builder webClientBuilder;

    private final StreamSessionRepository streamSessionRepository ;

    @Autowired
    private ObsWebSocketService obsWebSocketService;

    @Value("${kick.api.client-id}")
    private String clientId;

    @Value("${kick.api.client-secret}")
    private String clientSecret;

    @Value("${kick.api.redirect-uri}")
    private String redirectUri;

    @Value("${kick.api.token-url}")
    private String tokenUrl;

    @Value("${kick.api.base-url}")
    private String baseUrl;

    
    @Override
    public boolean updateStreamStatusOnKick(String accessToken, boolean enable) {
        try {
            WebClient webClient = webClientBuilder.build();
            
            // Creamos el body con la estructura que espera la API pública de Kick
            Map<String, Object> body = new HashMap<>();
            body.put("is_live", enable);
            
            webClient.patch()
            .uri("https://api.kick.com/public/v1/channels") // Endpoint de Kick para actualizar el canal
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .toBodilessEntity()
            .block();
            
            return true; // Si no lanzó excepción, la llamada a Kick fue exitosa
        } catch (Exception e) {
            // En producción puedes registrar el error con log.error("Error al actualizar Kick", e);
            return false;
        }
    }
    
    @Override
    public HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId, ToggleStreamStateRequestDto request) {

        // 1. Buscar la sesión en base de datos
        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("Sesión de streaming no encontrada para el evento ID: " + eventId));

        // 2. Determinar si debemos activar o desactivar
        boolean isEnable;
        if (request != null && request.getEnable() != null) {
            isEnable = request.getEnable();
        } else {
            // Si no viene la bandera enable, alternamos (toggle) según el estado actual
            isEnable = !StatusStream.LIVE.equals(streamSession.getStatusStream());
        }

        // 3. Obtener token de Kick si existe
        String accessToken = (streamSession.getKickOAuthToken() != null) 
                ? streamSession.getKickOAuthToken().getAccessToken() 
                : null;

        // 4. Iniciar o detener emisión en OBS Studio y actualizar estado en BD
        if (isEnable) {
            // Configurar RTMP y clave de emisión en OBS si están presentes, e iniciar transmisión
            if (streamSession.getRtmpUrl() != null && !streamSession.getRtmpUrl().isBlank()
                    && streamSession.getStreamKey() != null && !streamSession.getStreamKey().isBlank()) {
                obsWebSocketService.setStreamSettingsAndStart(streamSession.getRtmpUrl(), streamSession.getStreamKey());
            } else {
                obsWebSocketService.startStreaming();
            }

            streamSession.setStatusStream(StatusStream.LIVE);

            if (streamSession.getTimestamps() == null) {
                streamSession.setTimestamps(new Timestamps());
            }
            if (streamSession.getTimestamps().getStartedAt() == null) {
                streamSession.getTimestamps().setStartedAt(Instant.now());
            }

            if (streamSession.getLiveOverlayData() != null) {
                streamSession.getLiveOverlayData().setIsActive(true);
            }
        } else {
            // Envía la orden a OBS Studio para detener la emisión
            obsWebSocketService.stopStreaming();

            streamSession.setStatusStream(StatusStream.FINISHED);

            if (streamSession.getTimestamps() != null) {
                streamSession.getTimestamps().setEndAt(Instant.now());
            }

            if (streamSession.getLiveOverlayData() != null) {
                streamSession.getLiveOverlayData().setIsActive(false);
            }
        }

        // 5. Notificar a Kick API si se cuenta con token de OAuth
        if (accessToken != null && !accessToken.isBlank()) {
            this.updateStreamStatusOnKick(accessToken, isEnable);
        }

        // Guardar cambios en la BD
        streamSessionRepository.save(streamSession);

        // 6. Mapear la respuesta DTO
        StreamPublicResponseDto publicResponseDto = new StreamPublicResponseDto();
        publicResponseDto.setEventId(streamSession.getEventId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());
        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        // 7. Respuesta HTTP
        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Estado del directo en OBS, Kick y BD actualizado a: " + streamSession.getStatusStream());

        return response;
    }
   
     @Override
    public KickOAuthToken exchangeCodeForTokens(String code, String codeVerifier) {
        WebClient webClient = webClientBuilder.build();

        // Si por alguna razón no tenemos el verifier, enviamos uno por defecto (o el generado)
        String verifierToSend = (codeVerifier != null && !codeVerifier.isBlank()) ? codeVerifier : "0123456789012345678901234567890123456789012";

        KickTokenResponseDto response = webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectUri)
                        .with("code", code)
                        .with("code_verifier", verifierToSend)) // 👈 ESTE PARÁMETRO FALTABA
                .retrieve()
                .bodyToMono(KickTokenResponseDto.class)
                .block();

        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Error al obtener los tokens de acceso de Kick");
        }

        return KickOAuthToken.builder()
                .accessToken(response.getAccessToken())
                .refreshToken(response.getRefreshToken())
                .expiresIn(response.getExpiresIn())
                .tokenType(response.getTokenType())
                .scope(response.getScope())
                .obtainedAt(Instant.now())
                .build();
    }

    @Override
    public KickOAuthToken refreshAccessToken(String refreshToken) {
        WebClient webClient = webClientBuilder.build();

        KickTokenResponseDto response = webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("refresh_token", refreshToken))
                .retrieve()
                .bodyToMono(KickTokenResponseDto.class)
                .block();

        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Error al refrescar el token de acceso de Kick");
        }

        return KickOAuthToken.builder()
                .accessToken(response.getAccessToken())
                .refreshToken(response.getRefreshToken() != null ? response.getRefreshToken() : refreshToken)
                .expiresIn(response.getExpiresIn())
                .tokenType(response.getTokenType())
                .scope(response.getScope())
                .obtainedAt(Instant.now())
                .build();
    }

    
    @Override
    public String generateAuthorizationUrl(String state) {
    try {
        // Usamos un verifier fijo para el entorno de desarrollo local (mínimo 43 caracteres)
        String codeVerifier = "WD_STREAMING_DEVELOPMENT_CODE_VERIFIER_1234567890";

        // Generar Challenge S256 a partir del verifier fijo
        byte[] bytes = codeVerifier.getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        return String.format(
            "%s?response_type=code&client_id=%s&redirect_uri=%s&scope=%s&code_challenge=%s&code_challenge_method=S256&state=%s",
            "https://id.kick.com/oauth/authorize",
            clientId,
            URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
            URLEncoder.encode("channel:read channel:write", StandardCharsets.UTF_8),
            codeChallenge,
            state
        );
        } catch (Exception e) {
            throw new RuntimeException("Error al generar la URL de autorización", e);
        }
    }

    @Override
    public Map<String, String> getStreamCredentials(String accessToken) {
        try {
            WebClient webClient = webClientBuilder.build();

            // Consulta a la API de Kick para obtener datos del canal/stream
            return webClient.get()
                    .uri("https://api.kick.com/public/v1/channels")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Error al obtener credenciales de OBS desde Kick: " + e.getMessage());
        }
    }

}



