package com.world_dance.ms_notification_._streaming.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.world_dance.ms_notification_._streaming.exception.InvalidStreamRequestException;
import com.world_dance.ms_notification_._streaming.exception.KickUnauthorizedException;
import com.world_dance.ms_notification_._streaming.exception.StreamSessionNotFoundException;
import com.world_dance.ms_notification_._streaming.service.KickApiClientService;
import com.world_dance.ms_notification_._streaming.service.StreamSessionService;
import com.world_dance.wd_lib_common.dto.CreateStreamSessionRequestDto;
import com.world_dance.wd_lib_common.dto.FinishStreamRequestDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.LiveStreamResponseDto;
import com.world_dance.wd_lib_common.dto.StreamAdminResponseDto;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.ToggleStreamStateRequestDto;
import com.world_dance.wd_lib_common.dto.UpdateOverlayRequesDto;
import com.world_dance.wd_lib_common.dto.UpdateStreamConfigRequestDto;
import com.world_dance.wd_lib_common.entity.KickOAuthToken;
import com.world_dance.wd_lib_common.entity.StreamSession;
import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Controlador REST encargado de administrar el ciclo de vida y los endpoints de transmisión en vivo.
 * Permite a los usuarios autorizados (creador/ownerId del evento, STAFF y ADMIN) crear sesiones de stream,
 * controlar el encendido y apagado del directo (Start/Stop), actualizar el overlay de puntajes en tiempo real,
 * autenticarse con la API de Kick mediante OAuth 2.0 y consultar información pública y administrativa.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/stream")
public class StreamSessionController {

    private final StreamSessionService streamSessionService;
    private final KickApiClientService kickApiClientService;
    private final StreamSessionRepository streamSessionRepository;

    /**
     * Origen del frontend al que se redirige tras el callback OAuth de Kick. En Docker/producción
     * es https://worlddance.win; en desarrollo local, exporta FRONTEND_BASE_URL=http://localhost:4200
     * para que la redirección resuelva contra `ng serve` en vez del dominio público.
     */
    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    /**
     * Crea una nueva sesión de transmisión en vivo para un evento específico.
     * Realiza las comprobaciones de autorización para garantizar que únicamente el ownerId del evento,
     * o usuarios con rol STAFF o ADMIN en el evento puedan crear la sesión de transmisión.
     *
     * @param authenticatedUserId ID del usuario autenticado proveniente del encabezado X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en el encabezado X-User-Role (opcional)
     * @param request             DTO con los datos requeridos para la creación de la sesión
     * @return respuesta global con los detalles administrativos del stream y código 201 CREATED
     */
    @PostMapping("/createStreamSession")
    public ResponseEntity<HttpGlobalResponse<StreamAdminResponseDto>> createStreaminSeassion(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleHeader,
            @Valid @RequestBody CreateStreamSessionRequestDto request) {
        try {
            HttpGlobalResponse<StreamAdminResponseDto> response = streamSessionService.createStreamSession(request, authenticatedUserId, userRoleHeader);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (SecurityException e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (Exception e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * Consulta pública de los detalles de la sesión de transmisión por ID del evento (IFrame de reproductor, chat, VODs).
     * Accesible por cualquier usuario o participante público.
     *
     * @param eventId ID del evento consultado
     * @return respuesta global con el DTO público de la sesión de transmisión
     */
    @GetMapping("/event/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> getStreamByEventId(
            @PathVariable
            @NotNull(message = "El ID del evento es obligatorio")
            @Positive(message = "El ID del evento debe ser un número positivo") Long eventId) {
        try {
            HttpGlobalResponse<StreamPublicResponseDto> response = streamSessionService.getStreamByEventId(eventId);
            return ResponseEntity.ok(response);
        } catch (StreamSessionNotFoundException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            log.error("Error inesperado consultando el stream del evento {}: {}", eventId, e.getMessage(), e);
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Lista pública de todos los eventos actualmente en vivo (statusStream = LIVE). Alimenta el
     * indicador/menú "En Vivo" de la navbar; accesible por cualquier usuario, autenticado o no.
     *
     * @return respuesta global con la lista de eventos en vivo (eventId + nombre)
     */
    @GetMapping("/live")
    public ResponseEntity<HttpGlobalResponse<List<LiveStreamResponseDto>>> getLiveStreams() {
        HttpGlobalResponse<List<LiveStreamResponseDto>> response = streamSessionService.getLiveStreams();
        return ResponseEntity.ok(response);
    }

    /**
     * Actualiza los datos de la superposición gráfica (overlay) en vivo para el evento (puntaje en tiempo real, participante actual).
     * Requiere permisos de ownerId del evento, STAFF o ADMIN.
     *
     * @param authenticatedUserId ID del usuario autenticado proveniente del encabezado X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en el encabezado X-User-Role
     * @param eventId             ID del evento
     * @param request             DTO con la información actualizada del overlay
     * @return respuesta global con los datos del stream actualizados
     */
    @PutMapping("/updateOverlay/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> updateOverlay(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleHeader,
            @PathVariable
            @NotNull(message = "El ID del evento es obligatorio")
            @Positive(message = "El ID del evento debe ser un número positivo") Long eventId,
            @Valid @RequestBody UpdateOverlayRequesDto request) {
        try {
            HttpGlobalResponse<StreamPublicResponseDto> response = streamSessionService.updateOverlay(eventId, request, authenticatedUserId, userRoleHeader);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (StreamSessionNotFoundException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            log.error("Error inesperado actualizando el overlay del evento {}: {}", eventId, e.getMessage(), e);
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Finaliza una sesión de transmisión por ID de sesión y registra los datos del VOD.
     * Requiere permisos de ownerId del evento, STAFF o ADMIN.
     *
     * @param authenticatedUserId ID del usuario autenticado
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     * @param streamId            ID de la sesión de transmisión en MongoDB
     * @param request             DTO con los datos del VOD
     * @return respuesta global con el DTO público final del stream
     */
    @PatchMapping("/finish/{streamId}")
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> finishStream(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleHeader,
            @PathVariable String streamId,
            @RequestBody FinishStreamRequestDto request) {
        try {
            HttpGlobalResponse<StreamPublicResponseDto> response = streamSessionService.finishStream(streamId, request, authenticatedUserId, userRoleHeader);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (StreamSessionNotFoundException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            log.error("Error inesperado finalizando la sesión {}: {}", streamId, e.getMessage(), e);
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Enciende o apaga la transmisión en vivo (Start/Stop) para un evento específico.
     * Controla el contenedor Docker en ffmpeg-manager, actualiza la API de Kick y el estado en MongoDB.
     * Requiere permisos de autorización (ownerId del evento, STAFF o ADMIN).
     *
     * @param authenticatedUserId ID del usuario autenticado proveniente de X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en X-User-Role
     * @param eventId             ID único del evento a controlar
     * @param request             DTO opcional con banderas enable, sourceType y destinationUrl
     * @param enable              parámetro opcional en URL query
     * @return respuesta global con el DTO actualizado de la sesión
     */
    @RequestMapping(value = "/toggleState/{eventId}", method = {org.springframework.web.bind.annotation.RequestMethod.PATCH, org.springframework.web.bind.annotation.RequestMethod.POST, org.springframework.web.bind.annotation.RequestMethod.GET})
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> toggleStreamState(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleHeader,
            @PathVariable
            @NotNull(message = "El ID del evento es obligatorio")
            @Positive(message = "El ID del evento debe ser un número positivo") Long eventId,
            @RequestBody(required = false) ToggleStreamStateRequestDto request,
            @RequestParam(required = false) Boolean enable) {
        try {
            if (request == null && enable != null) {
                request = new ToggleStreamStateRequestDto(enable);
            }
            HttpGlobalResponse<StreamPublicResponseDto> response = kickApiClientService.toggleStreamState(eventId, request, authenticatedUserId, userRoleHeader);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (StreamSessionNotFoundException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (InvalidStreamRequestException e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (KickUnauthorizedException e) {
            // 400, NO 401: el interceptor global del frontend trata cualquier 401 como "expiró la
            // sesión del usuario en WorldDance" y fuerza un logout — este 401 es de Kick, no del JWT
            // del usuario, así que un 401 real aquí desloguearía a alguien por error.
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            // Aquí caen, entre otros, los fallos reales de encendido: ffmpeg-manager inalcanzable,
            // contenedor Docker que no arranca, etc. Antes se reportaban como 400 (Bad Request), lo
            // cual sugería un problema con los datos enviados cuando en realidad es un fallo del
            // lado del servidor/infraestructura al intentar iniciar la transmisión.
            log.error("Error inesperado en toggleState para el evento {}: {}", eventId, e.getMessage(), e);
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Consulta el estado en tiempo real del proceso de transmisión en ffmpeg-manager.
     *
     * @param eventId ID del evento
     * @return respuesta global con el mapa de estado (RUNNING, STOPPED, FAILED, etc.)
     */
    @GetMapping("/status/{eventId}")
    public ResponseEntity<HttpGlobalResponse<Map<String, Object>>> getStreamStatus(
            @PathVariable
            @NotNull(message = "El ID del evento es obligatorio")
            @Positive(message = "El ID del evento debe ser un número positivo") Long eventId) {
        try {
            Map<String, Object> statusMap = kickApiClientService.getStreamStatus(eventId);
            HttpGlobalResponse<Map<String, Object>> response = new HttpGlobalResponse<>();
            response.setData(statusMap);
            response.setMessage("Estado del stream consultado correctamente.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HttpGlobalResponse<Map<String, Object>> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    /**
     * Callback OAuth 2.0 de Kick para recibir el código de autorización e intercambiarlo por tokens.
     *
     * A diferencia del resto del controlador, esto lo visita directamente el navegador del
     * organizador (no una llamada AJAX del frontend): Kick redirige aquí tras la autorización. Por
     * eso responde con una redirección 302 de vuelta al panel de administración en lugar de un JSON
     * crudo — y por lo mismo nunca se devuelve el access token en la respuesta HTTP visible.
     *
     * @param code  código de autorización enviado por Kick
     * @param state parámetro opcional conteniendo el eventId
     * @return 302 hacia /stream/admin/{eventId}?kick=success (o ?kick=error si algo falla)
     */
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> handleKickCallback(
            @RequestParam("code") String code,
            @RequestParam(value = "state", required = false) String state) {

        String codeVerifier = "WD_STREAMING_DEVELOPMENT_CODE_VERIFIER_1234567890";
        String fallbackUrl = frontendBaseUrl + "/stream/admin?kick=error";

        try {
            KickOAuthToken tokens = kickApiClientService.exchangeCodeForTokens(code, codeVerifier);

            if (state == null) {
                return redirectTo(fallbackUrl);
            }

            Long eventId = Long.parseLong(state);
            StreamSession session = streamSessionRepository.findByEventId(eventId)
                    .orElseThrow(() -> new RuntimeException("No se encontró sesión para el evento: " + eventId));

            session.setKickOAuthToken(tokens);
            streamSessionRepository.save(session);

            return redirectTo(frontendBaseUrl + "/stream/admin/" + eventId + "?kick=success");
        } catch (Exception e) {
            log.warn("Fallo al procesar el callback OAuth de Kick: {}", e.getMessage());
            return redirectTo(fallbackUrl);
        }
    }

    private ResponseEntity<Void> redirectTo(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    /**
     * Genera y retorna la URL de autorización para iniciar sesión con la cuenta de Kick.
     *
     * @param state parámetro opcional para mantener contexto (ID del evento)
     * @return respuesta global con la URL de login
     */
    @GetMapping("/oauth/kick/login")
    public ResponseEntity<HttpGlobalResponse<String>> getKickAuthUrl(@RequestParam(defaultValue = "1") String state) {
        String authUrl = kickApiClientService.generateAuthorizationUrl(state);

        HttpGlobalResponse<String> response = new HttpGlobalResponse<>();
        response.setData(authUrl);
        response.setMessage("Abre el enlace adjunto en el navegador para autorizar Kick.");
        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene las credenciales de transmisión RTMP asociadas al token OAuth de Kick.
     *
     * @param streamId ID de la sesión de transmisión
     * @return respuesta global con las credenciales
     */
    @GetMapping("/credentials/{streamId}")
    public ResponseEntity<HttpGlobalResponse<Map<String, String>>> getObsCredentials(@PathVariable String streamId) {
        HttpGlobalResponse<Map<String, String>> response = streamSessionService.getObsCredentials(streamId);
        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene la información administrativa completa de una sesión de transmisión (credenciales RTMP e ingestUrl WebSocket).
     * Requiere permisos de ownerId del evento, STAFF o ADMIN.
     *
     * @param authenticatedUserId ID del usuario autenticado proveniente de X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en X-User-Role
     * @param eventId             ID del evento consultado
     * @return respuesta global con el DTO administrativo
     */
    @GetMapping("/admin/event/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamAdminResponseDto>> getAdminStreamByEventId(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleHeader,
            @PathVariable
            @NotNull(message = "El ID del evento es obligatorio")
            @Positive(message = "El ID del evento debe ser un número positivo") Long eventId) {
        try {
            HttpGlobalResponse<StreamAdminResponseDto> response = streamSessionService.getAdminStreamByEventId(eventId, authenticatedUserId, userRoleHeader);
            response.setMessage("Información de la sesión de transmisión obtenida correctamente.");
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (StreamSessionNotFoundException e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            // Antes CUALQUIER excepción aquí (timeout de Feign hacia ms-event-category/ms-enrollment,
            // NPE, error de Mongo, etc.) se devolvía como 404 "no existe", ocultando el error real
            // detrás de un mensaje que sugería falsamente que la sesión nunca se creó.
            log.error("Error inesperado consultando el panel admin del evento {}: {}", eventId, e.getMessage(), e);
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Actualiza la configuración de ingesta/retransmisión de una sesión ya creada (servidor RTMP,
     * clave de retransmisión, canal, título/descripción). Requiere permisos de ownerId del evento,
     * STAFF o ADMIN. No modifica el estado de la sesión (statusStream): eso lo gobiernan
     * toggleState/finishStream.
     *
     * @param authenticatedUserId ID del usuario autenticado proveniente de X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en X-User-Role
     * @param eventId             ID del evento cuya sesión se edita
     * @param request             DTO con los nuevos valores de configuración
     * @return respuesta global con el DTO administrativo actualizado
     */
    @PutMapping("/config/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamAdminResponseDto>> updateStreamConfig(
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleHeader,
            @PathVariable
            @NotNull(message = "El ID del evento es obligatorio")
            @Positive(message = "El ID del evento debe ser un número positivo") Long eventId,
            @Valid @RequestBody UpdateStreamConfigRequestDto request) {
        try {
            HttpGlobalResponse<StreamAdminResponseDto> response = streamSessionService.updateStreamConfig(eventId, request, authenticatedUserId, userRoleHeader);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (StreamSessionNotFoundException e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            log.error("Error inesperado actualizando la configuración del evento {}: {}", eventId, e.getMessage(), e);
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
