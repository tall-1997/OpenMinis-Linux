package com.openminis.app.hostcapability.api

interface HostCapabilityHandler {
    val name: String
    val permission: CapabilityPermission get() = CapabilityPermission.NONE
    suspend fun handle(request: HostCapabilityRequest): HostCapabilityResponse
}
