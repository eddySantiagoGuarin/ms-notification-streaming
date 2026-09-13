package com.world_dance.ms_notification_._streaming.exception;

/**
 * Se lanza cuando una solicitud para operar la transmisión (ej. encenderla) llega con datos
 * insuficientes para producir una señal de video real — típicamente una fuente de video
 * (`sourceType`) ausente o inválida. Antes de existir esta validación explícita, ffmpeg-manager
 * sustituía silenciosamente cualquier `sourceType` no reconocido por `testsrc` (un patrón
 * sintético), así que un valor vacío/erróneo terminaba "encendiendo" el stream y marcando
 * `is_live: true` en Kick sin que ninguna señal real llegara nunca a la fuente esperada.
 */
public class InvalidStreamRequestException extends RuntimeException {

    public InvalidStreamRequestException(String message) {
        super(message);
    }
}
