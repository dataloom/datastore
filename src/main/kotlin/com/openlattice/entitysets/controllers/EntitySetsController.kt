/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 */

package com.openlattice.entitysets.controllers

import com.google.common.base.Preconditions
import com.openlattice.authorization.AclKey
import com.openlattice.authorization.AuthorizationManager
import com.openlattice.authorization.AuthorizingComponent
import com.openlattice.datastore.services.EdmManager
import com.openlattice.entitysets.EntitySetsApi
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.springframework.web.bind.annotation.*
import java.util.*
import javax.inject.Inject

@RestController
@RequestMapping(EntitySetsApi.CONTROLLER)
class EntitySetsController @Inject
constructor(
        private val authorizationManager: AuthorizationManager,
        private val edmManager: EdmManager
) : EntitySetsApi, AuthorizingComponent {

    private val PERSON_FQN = "general.person"

    @RequestMapping(path = [EntitySetsApi.LINKING + EntitySetsApi.SET_ID_PATH], method = [RequestMethod.PUT])
    override fun addEntitySetsToLinkingEntitySet(
            @PathVariable(EntitySetsApi.SET_ID) linkingEntitySetId: UUID,
            @RequestBody entitySetIds: Set<UUID>
    ): Int {
        return addEntitySets(linkingEntitySetId, entitySetIds)
    }

    @RequestMapping(path = [EntitySetsApi.LINKING], method = [RequestMethod.POST])
    override fun addEntitySetsToLinkingEntitySets(@RequestBody entitySetIds: Map<UUID, Set<UUID>>): Int {
        return entitySetIds.map { addEntitySets(it.key, it.value) }.sum()
    }

    private fun addEntitySets(linkingEntitySetId: UUID, entitySetIds: Set<UUID>): Int {
        ensureOwnerAccess(AclKey(linkingEntitySetId))
        Preconditions.checkState(
                edmManager.getEntitySet(linkingEntitySetId).isLinking,
                "Can't add linked entity sets to a not linking entity set")
        checkLinkedEntitySets(entitySetIds)
        ensureValidLinkedEntitySets(entitySetIds)

        return edmManager.addLinkedEntitySets(linkingEntitySetId, entitySetIds)
    }

    @RequestMapping(path = [EntitySetsApi.LINKING + EntitySetsApi.SET_ID_PATH], method = [RequestMethod.DELETE])
    override fun removeEntitySetsFromLinkingEntitySet(
            @PathVariable(EntitySetsApi.SET_ID) linkingEntitySetId: UUID,
            @RequestBody entitySetIds: Set<UUID>
    ): Int {
        return removeEntitySets(linkingEntitySetId, entitySetIds)
    }

    @RequestMapping(path = [EntitySetsApi.LINKING], method = [RequestMethod.DELETE])
    override fun removeEntitySetsFromLinkingEntitySets(@RequestBody entitySetIds: Map<UUID, Set<UUID>>): Int {
        return entitySetIds.map { removeEntitySets(it.key, it.value) }.sum()
    }

    private fun removeEntitySets(linkingEntitySetId: UUID, entitySetIds: Set<UUID>): Int {
        ensureOwnerAccess(AclKey(linkingEntitySetId))
        Preconditions.checkState(
                edmManager.getEntitySet(linkingEntitySetId).isLinking,
                "Can't remove linked entity sets from a not linking entity set")
        checkLinkedEntitySets(entitySetIds)

        return edmManager.removeLinkedEntitySets(linkingEntitySetId, entitySetIds)
    }

    private fun checkLinkedEntitySets(entitySetIds: Set<UUID>) {
        checkNotNull(entitySetIds)
        Preconditions.checkState(!entitySetIds.isEmpty(), "Linked entity sets is empty")
    }

    private fun ensureValidLinkedEntitySets(entitySetIds: Set<UUID>) {
        val entityTypeId = edmManager.getEntityType(FullQualifiedName(PERSON_FQN)).id
        Preconditions.checkState(
                entitySetIds.stream()
                        .map { edmManager.getEntitySet(it).entityTypeId }
                        .allMatch { entityTypeId == it },
                "Linked entity sets are of differing entity types than %s :{}",
                PERSON_FQN, entitySetIds)

        Preconditions.checkState(
                entitySetIds.all { !edmManager.getEntitySet(it).isLinking },
                "Cannot add linking entity set as linked entity set.")
    }

    override fun getAuthorizationManager(): AuthorizationManager {
        return authorizationManager
    }
}