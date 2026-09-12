package com.world_dance.ms_notification_._streaming.exception;

/**
 * Se lanza exclusivamente cuando una búsqueda de {@code StreamSession} (por eventId o por el _id
 * de Mongo) no encuentra ningún documento. Antes de existir esta clase, todas las fallas del
 * servicio (timeouts de Feign, NPEs, errores de Mongo, etc.) se representaban con
 * {@link RuntimeException} genéricas, y el controlador las traducía TODAS a HTTP 404 — lo que
 * hacía que un problema real (ej. un fallo transitorio de red) se viera idéntico a "la sesión
 * realmente no existe", confundiendo el diagnóstico. Ahora el controlador puede distinguir el
 * 404 legítimo (esta excepción) de cualquier otro fallo inesperado (500).
 */
public class StreamSessionNotFoundException extends RuntimeException {

    public StreamSessionNotFoundException(String message) {
        super(message);
    }
}
