package com.world_dance.ms_notification_._streaming.service;

import java.util.Map;

import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.ToggleStreamStateRequestDto;
import com.world_dance.wd_lib_common.entity.KickOAuthToken;

/**
 * Servicio de interfaz para la interacción con la API pública de Kick y el control de transmisiones en vivo.
 * Define métodos para autenticación OAuth 2.0, actualización del estado del canal en Kick,
 * conmutación de estado de emisión (Start/Stop) mediante ffmpeg-manager y consulta de credenciales.
 */
public interface KickApiClientService {

    /**
     * Intercambia un código de autorización OAuth por tokens de acceso y refresco de Kick.
     *
     * @param code         código de autorización recibido en la llamada callback
     * @param codeVerifier verificador PKCE utilizado en la solicitud de autorización
     * @return entidad KickOAuthToken con las credenciales persistibles
     */
    KickOAuthToken exchangeCodeForTokens(String code, String codeVerifier);

    /**
     * Renueva el token de acceso de Kick utilizando el token de refresco existente.
     *
     * @param refreshToken token de refresco de OAuth 2.0
     * @return nueva entidad KickOAuthToken actualizada
     */
    KickOAuthToken refreshAccessToken(String refreshToken);

    /**
     * Actualiza el estado de la transmisión directa (is_live) en el canal de Kick.
     *
     * @param accessToken token de acceso Bearer del usuario autenticado en Kick
     * @param enable      booleano indicando si el directo pasa a estar activo o inactivo
     * @return true si la actualización fue aceptada por la API de Kick; false en caso contrario
     */
    boolean updateStreamStatusOnKick(String accessToken, boolean enable);

    /**
     * Genera la URL de autorización OAuth 2.0 con desafío PKCE S256 para iniciar el login con Kick.
     *
     * @param state parámetro de estado arbitrario para preservar contexto (ej. ID del evento)
     * @return URL completa de autorización lista para redireccionar en el navegador
     */
    String generateAuthorizationUrl(String state);

    /**
     * Alterna o establece el estado de transmisión (LIVE / FINISHED) para un evento específico.
     * Sobrecarga conveniente sin requerir ID de usuario ni rol.
     *
     * @param eventId ID único del evento a controlar
     * @param request DTO opcional con parámetros como sourceType y destinationUrl
     * @return respuesta global con el DTO público de la sesión de transmisión
     */
    HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId, ToggleStreamStateRequestDto request);

    /**
     * Alterna o establece el estado de transmisión (LIVE / FINISHED) para un evento específico.
     * Valida permisos de administración (ownerId del evento, STAFF o ADMIN), invoca a ffmpeg-manager
     * para iniciar o destruir el contenedor Docker de FFmpeg, notifica a la API de Kick y actualiza MongoDB.
     *
     * @param eventId             ID único del evento a controlar
     * @param request             DTO opcional con parámetros como sourceType y destinationUrl
     * @param authenticatedUserId ID del usuario autenticado proveniente del encabezado X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en el encabezado X-User-Role
     * @return respuesta global con el DTO público de la sesión de transmisión
     */
    HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId, ToggleStreamStateRequestDto request, Long authenticatedUserId, String userRoleHeader);


    /**
     * Obtiene las credenciales de transmisión RTMP (URL y Stream Key) asociadas a la cuenta de Kick.
     *
     * @param accessToken token de acceso de Kick
     * @return mapa con pares clave-valor conteniendo las credenciales de la plataforma
     */
    Map<String, String> getStreamCredentials(String accessToken);

    /**
     * Consulta el estado en tiempo real del proceso de emisión en ffmpeg-manager para un evento.
     *
     * @param eventId ID único del evento consultado
     * @return mapa estructurado con los detalles de estado (RUNNING, STOPPED, FAILED, etc.)
     */
    Map<String, Object> getStreamStatus(Long eventId);
}
