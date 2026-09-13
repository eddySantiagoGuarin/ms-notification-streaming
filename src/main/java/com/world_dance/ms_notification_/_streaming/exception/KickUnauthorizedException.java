package com.world_dance.ms_notification_._streaming.exception;

/**
 * Se lanza cuando Kick responde 401 Unauthorized al intentar actualizar el estado del canal
 * (PATCH /public/v1/channels): el access token OAuth guardado expiró o quedó inválido.
 *
 * Deliberadamente se mapea a HTTP 400 (Bad Request) en el controlador, NO a 401: el interceptor
 * global del frontend (`error.interceptor.ts`) trata CUALQUIER 401 como "la sesión del usuario en
 * WorldDance expiró" y fuerza un logout + redirect a /auth/login. Un 401 aquí (que en realidad
 * significa "el token de Kick, no el JWT del usuario, expiró") cerraría la sesión del usuario en
 * la app por error.
 */
public class KickUnauthorizedException extends RuntimeException {

    public KickUnauthorizedException(String message) {
        super(message);
    }
}
