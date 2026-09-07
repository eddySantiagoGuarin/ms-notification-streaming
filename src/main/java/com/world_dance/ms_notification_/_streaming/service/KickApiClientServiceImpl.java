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
import org.springframework.context.annotation.Lazy;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de implementación para la integración con la API de Kick y el gestor
 * de transmisiones FFmpeg.
 * Maneja la autenticación OAuth 2.0 con flujo PKCE, el control de ciclo de vida
 * de los streams (Start/Stop)
 * vía comunicación HTTP con ffmpeg-manager, y la actualización de estados en
 * MongoDB y Kick API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KickApiClientServiceImpl implements KickApiClientService {

    private final WebClient.Builder webClientBuilder;
    private final StreamSessionRepository streamSessionRepository;

    @Autowired
    @Lazy
    private StreamSessionService streamSessionService;

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

    @Value("${ffmpeg.manager.url:http://localhost:3000}")
    private String ffmpegManagerUrl;

    /**
     * Actualiza el estado público del canal en la API de Kick (v1/channels).
     *
     * @param accessToken token Bearer del usuario autenticado en Kick
     * @param enable      booleano indicando el estado deseado para el canal (true =
     *                    LIVE, false = FINISHED)
     * @return true si la solicitud PATCH fue procesada correctamente; false en caso
     *         contrario
     */
    @Override
    public boolean updateStreamStatusOnKick(String accessToken, boolean enable) {
        try {
            WebClient webClient = webClientBuilder.build();

            Map<String, Object> body = new HashMap<>();
            body.put("is_live", enable);

            webClient.patch()
                    .uri("https://api.kick.com/public/v1/channels")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Estado del canal en Kick actualizado exitosamente a is_live: {}", enable);
            return true;
        } catch (Exception e) {
            log.warn("No se pudo actualizar el estado en Kick via API: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Alterna o establece el estado de transmisión (LIVE / FINISHED) para un
     * evento.
     * Sobrecarga de método sin encabezados de usuario para compatibilidad.
     *
     * @param eventId ID del evento
     * @param request DTO con los parámetros de la solicitud
     * @return respuesta global con los datos públicos de la transmisión
     */
    @Override
    public HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId,
            ToggleStreamStateRequestDto request) {
        return toggleStreamState(eventId, request, null, null);
    }

    /**
     * Alterna o establece el estado de transmisión (LIVE / FINISHED) para un
     * evento.
     * Valida permisos de administración (ownerId del evento, STAFF o ADMIN), invoca
     * a ffmpeg-manager
     * para iniciar o destruir el contenedor Docker de FFmpeg, notifica a Kick API y
     * actualiza MongoDB.
     *
     * @param eventId             ID del evento a controlar
     * @param request             DTO opcional con parámetros como sourceType y
     *                            destinationUrl
     * @param authenticatedUserId ID del usuario autenticado (X-User-Id)
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     *                            (X-User-Role)
     * @return respuesta global con el DTO público de la sesión de transmisión
     */
    @Override
    public HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId,
            ToggleStreamStateRequestDto request, Long authenticatedUserId, String userRoleHeader) {

        // 1. Validar permisos de administración (ownerId, STAFF o ADMIN)
        streamSessionService.validateAdminPermission(eventId, authenticatedUserId, userRoleHeader);

        // 2. Buscar la sesión en base de datos
        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(
                        () -> new RuntimeException("Sesión de streaming no encontrada para el evento ID: " + eventId));

        // 3. Determinar si debemos activar o desactivar
        boolean isEnable;
        if (request != null && request.getEnable() != null) {
            isEnable = request.getEnable();
        } else {
            // Si no viene la bandera enable, alternamos (toggle) según el estado actual
            isEnable = !StatusStream.LIVE.equals(streamSession.getStatusStream());
        }

        // 4. Obtener token de Kick si existe
        String accessToken = (streamSession.getKickOAuthToken() != null)
                ? streamSession.getKickOAuthToken().getAccessToken()
                : null;

        // 5. Iniciar o detener emisión vía ffmpeg-manager y actualizar estado en BD
        if (isEnable) {
            String destUrl = (request != null && request.getDestinationUrl() != null
                    && !request.getDestinationUrl().isBlank())
                            ? request.getDestinationUrl()
                            : (streamSession.getRtmpUrl() != null
                                    ? streamSession.getRtmpUrl() + "/" + streamSession.getStreamKey()
                                    : "rtmp://wd-srs-media-server:1935/live/" + eventId);

            String sourceType = (request != null && request.getSourceType() != null
                    && !request.getSourceType().isBlank())
                            ? request.getSourceType()
                            : "testsrc";

            // Iniciar transmisión en ffmpeg-manager
            try {
                WebClient webClient = webClientBuilder.build();
                Map<String, Object> ffmpegStartReq = new HashMap<>();
                ffmpegStartReq.put("streamId", eventId.toString());
                ffmpegStartReq.put("destinationUrl", destUrl);
                ffmpegStartReq.put("sourceType", sourceType);

                webClient.post()
                        .uri(ffmpegManagerUrl + "/api/stream/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ffmpegStartReq)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                log.info("FFmpeg manager iniciado exitosamente para el evento ID: {} con destino: {}", eventId,
                        destUrl);
            } catch (Exception e) {
                log.error("Fallo al iniciar transmisión en ffmpeg-manager ({}/api/stream/start): {}", ffmpegManagerUrl,
                        e.getMessage());
                throw new RuntimeException("No se pudo iniciar la transmisión en ffmpeg-manager: " + e.getMessage());
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
            // Detener transmisión en ffmpeg-manager
            try {
                WebClient webClient = webClientBuilder.build();
                Map<String, Object> ffmpegStopReq = new HashMap<>();
                ffmpegStopReq.put("streamId", eventId.toString());

                webClient.post()
                        .uri(ffmpegManagerUrl + "/api/stream/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ffmpegStopReq)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                log.info("FFmpeg manager detenido exitosamente para el evento ID: {}", eventId);
            } catch (Exception e) {
                log.error("Fallo al detener transmisión en ffmpeg-manager ({}/api/stream/stop): {}", ffmpegManagerUrl,
                        e.getMessage());
                throw new RuntimeException("No se pudo apagar la transmisión en ffmpeg-manager: " + e.getMessage());
            }

            streamSession.setStatusStream(StatusStream.FINISHED);

            if (streamSession.getTimestamps() != null) {
                streamSession.getTimestamps().setEndAt(Instant.now());
            }

            if (streamSession.getLiveOverlayData() != null) {
                streamSession.getLiveOverlayData().setIsActive(false);
            }
        }

        // 6. Notificar a Kick API si se cuenta con token de OAuth
        if (accessToken != null && !accessToken.isBlank()) {
            this.updateStreamStatusOnKick(accessToken, isEnable);
        }

        // Guardar cambios en la BD
        streamSessionRepository.save(streamSession);

        // 7. Mapear la respuesta DTO
        StreamPublicResponseDto publicResponseDto = new StreamPublicResponseDto();
        publicResponseDto.setId(streamSession.getId());
        publicResponseDto.setEventId(streamSession.getEventId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());

        if (streamSession.getPlatformConfing() != null) {
            publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
            publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        }

        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Estado del directo en OBS, Kick y BD actualizado a: " + streamSession.getStatusStream());

        return response;
    }

    /**
     * Intercambia un código de autorización por tokens de acceso de Kick enviando
     * el verificador PKCE.
     */
    @Override
    public KickOAuthToken exchangeCodeForTokens(String code, String codeVerifier) {
        WebClient webClient = webClientBuilder.build();

        String verifierToSend = (codeVerifier != null && !codeVerifier.isBlank()) ? codeVerifier
                : "0123456789012345678901234567890123456789012";

        KickTokenResponseDto response = webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectUri)
                        .with("code", code)
                        .with("code_verifier", verifierToSend))
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

    /**
     * Refresca el token de acceso de Kick mediante el grant_type refresh_token.
     */
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

    /**
     * Genera la URL de autorización OAuth 2.0 con PKCE S256 para el inicio de
     * sesión con Kick.
     */
    @Override
    public String generateAuthorizationUrl(String state) {
        try {
            String codeVerifier = "WD_STREAMING_DEVELOPMENT_CODE_VERIFIER_1234567890";

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
                    state);
        } catch (Exception e) {
            throw new RuntimeException("Error al generar la URL de autorización", e);
        }
    }

    /**
     * Obtiene las credenciales de transmisión de Kick asociadas a la cuenta.
     */
    @Override
    public Map<String, String> getStreamCredentials(String accessToken) {
        try {
            WebClient webClient = webClientBuilder.build();

            return webClient.get()
                    .uri("https://api.kick.com/public/v1/channels")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {
                    })
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Error al obtener credenciales de OBS desde Kick: " + e.getMessage());
        }
    }

    /**
     * Consulta el estado en tiempo real del proceso en ffmpeg-manager.
     */
    @Override
    public Map<String, Object> getStreamStatus(Long eventId) {
        try {
            WebClient webClient = webClientBuilder.build();
            return webClient.get()
                    .uri(ffmpegManagerUrl + "/api/stream/status/" + eventId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
        } catch (Exception e) {
            Map<String, Object> errRes = new HashMap<>();
            errRes.put("streamId", eventId.toString());
            errRes.put("active", false);
            errRes.put("status", "NOT_RUNNING");
            errRes.put("message",
                    "No se encontró transmisión activa o fallo al conectar con ffmpeg-manager: " + e.getMessage());
            return errRes;
        }
    }
}
