package com.world_dance.ms_notification_._streaming.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.world_dance.wd_lib_common.dto.EventResponseDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;

/**
 * Cliente Feign para comunicarse con el microservicio ms-event-category.
 * Permite consultar la información administrativa y de propiedad (ownerId) de los eventos.
 */
@FeignClient(name = "ms-event-category", path = "/api/v1/events")
public interface EventCategoryFeignClient {

    /**
     * Consulta los detalles de un evento por su ID.
     *
     * @param eventId ID del evento a consultar
     * @return Respuesta global estructurada con el DTO del evento
     */
    @GetMapping("/{eventId}")
    HttpGlobalResponse<EventResponseDto> getEventById(@PathVariable("eventId") Long eventId);
}
