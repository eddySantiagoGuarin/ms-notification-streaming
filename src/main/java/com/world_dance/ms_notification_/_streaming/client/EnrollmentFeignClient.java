package com.world_dance.ms_notification_._streaming.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.world_dance.wd_lib_common.dto.UserEventRoleResponseDto;

/**
 * Cliente Feign para comunicarse con el microservicio ms-enrollment.
 * Permite validar el rol de un usuario en un evento específico (STAFF, ADMIN, JURY, etc.).
 */
@FeignClient(name = "ms-enrollment", path = "/api/v1/enrollments")
public interface EnrollmentFeignClient {

    /**
     * Consulta el rol asignado a un usuario dentro de un evento.
     *
     * @param eventId ID del evento
     * @param userId  ID del usuario
     * @return DTO con la información del rol del usuario en el evento
     */
    @GetMapping("/events/{eventId}/users/{userId}/role")
    UserEventRoleResponseDto getUserEventRole(@PathVariable("eventId") Long eventId, @PathVariable("userId") Long userId);
}
