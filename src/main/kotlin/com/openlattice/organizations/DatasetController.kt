package com.openlattice.organizations

import com.codahale.metrics.annotation.Timed
import com.google.common.base.Preconditions.checkState
import com.google.common.net.InetAddresses
import com.openlattice.authorization.*
import com.openlattice.organization.*
import com.openlattice.organization.OrganizationExternalDatabaseColumn
import com.openlattice.organization.OrganizationExternalDatabaseTable
import com.openlattice.organization.OrganizationExternalDatabaseTableColumnsPair
import com.openlattice.postgres.PostgresConnectionType
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import javax.inject.Inject
import java.util.UUID
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import com.openlattice.edm.requests.MetadataUpdate
import org.springframework.web.bind.annotation.PatchMapping






@SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Allowing kotlin collection mapping cast to List")
@RestController
@RequestMapping(CONTROLLER)
class DatasetController : DatasetApi, AuthorizingComponent {

    companion object {
        private val logger = LoggerFactory.getLogger(DatasetController::class.java)
    }

    @Inject
    private lateinit var edms: ExternalDatabaseManagementService

    @Inject
    private lateinit var authorizations: AuthorizationManager

    @Inject
    private lateinit var securableObjectTypes: SecurableObjectResolveTypeService

    @Inject
    private lateinit var aclKeyReservations: HazelcastAclKeyReservationService

    @Timed
    @PostMapping(path = [ID_PATH + USER_ID_PATH + CONNECTION_TYPE_PATH + EXTERNAL_DATABASE])
    override fun addHBARecord(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(USER_ID) userId: String,
            @PathVariable(CONNECTION_TYPE) connectionType: PostgresConnectionType,
            @RequestBody ipAddress: String
    ) {
        ensureOwnerAccess(AclKey(organizationId))
        validateHBAParameters(connectionType, ipAddress)
        val userPrincipal = Principal(PrincipalType.USER, userId)
        edms.addHBARecord(organizationId, userPrincipal, connectionType, ipAddress)
    }

    @Timed
    @DeleteMapping(path = [ID_PATH + USER_ID_PATH + CONNECTION_TYPE_PATH + EXTERNAL_DATABASE])
    override fun removeHBARecord(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(USER_ID) userId: String,
            @PathVariable(CONNECTION_TYPE) connectionType: PostgresConnectionType,
            @RequestBody ipAddress: String
    ) {
        ensureOwnerAccess(AclKey(organizationId))
        validateHBAParameters(connectionType, ipAddress)
        val userPrincipal = Principal(PrincipalType.USER, userId)
        edms.removeHBARecord(organizationId, userPrincipal, connectionType, ipAddress)
    }

    @Timed
    @GetMapping(path = [ID_PATH + EXTERNAL_DATABASE_TABLE])
    override fun getExternalDatabaseTables(
            @PathVariable(ID) organizationId: UUID): Set<OrganizationExternalDatabaseTable> {
        ensureOwnerAccess(AclKey(organizationId))
        return edms.getExternalDatabaseTables(organizationId)
    }

    @Timed
    @GetMapping(path = [ID_PATH + EXTERNAL_DATABASE_TABLE + EXTERNAL_DATABASE_COLUMN])
    override fun getExternalDatabaseTablesWithColumns(
            @PathVariable(ID) organizationId: UUID): Map<OrganizationExternalDatabaseTable, Set<OrganizationExternalDatabaseColumn>> {
        ensureOwnerAccess(AclKey(organizationId))
        return edms.getExternalDatabaseTablesWithColumns(organizationId)
    }

    @Timed
    @GetMapping(path = [ID_PATH + TABLE_ID_PATH + EXTERNAL_DATABASE_TABLE + EXTERNAL_DATABASE_COLUMN])
    override fun getExternalDatabaseTableWithColumns(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_ID) tableId: UUID): OrganizationExternalDatabaseTableColumnsPair {
        ensureReadAccess(AclKey(tableId))
        return edms.getExternalDatabaseTableWithColumns(tableId)
    }

    @Timed
    @GetMapping(path = [ID_PATH + TABLE_ID_PATH + ROW_COUNT_PATH + DATA])
    override fun getExternalDatabaseTableData(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_ID) tableId: UUID,
            @PathVariable(ROW_COUNT) rowCount: Int): Map<UUID, List<Any?>> {
        ensureReadAccess(AclKey(tableId))
        return edms.getExternalDatabaseTableData(organizationId, tableId, rowCount)
    }

    @Timed
    @GetMapping(path = [ID_PATH + TABLE_NAME_PATH + EXTERNAL_DATABASE_TABLE])
    override fun getExternalDatabaseTable(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String): OrganizationExternalDatabaseTable {
        val tableId = getExternalDatabaseObjectId(organizationId, tableName)
        ensureReadAccess(AclKey(tableId))
        return edms.getOrganizationExternalDatabaseTable(tableId)
    }

    @Timed
    @GetMapping(path = [ID_PATH + TABLE_NAME_PATH + COLUMN_NAME_PATH + EXTERNAL_DATABASE_COLUMN])
    override fun getExternalDatabaseColumn(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String,
            @PathVariable(COLUMN_NAME) columnName: String): OrganizationExternalDatabaseColumn {
        val tableId = getExternalDatabaseObjectId(organizationId, tableName)
        val columnId = getExternalDatabaseObjectId(tableId, columnName)
        ensureReadAccess(AclKey(tableId, columnId))
        return edms.getOrganizationExternalDatabaseColumn(columnId)
    }

    //TODO Metadata update probably won't work
    @Timed
    @PatchMapping(path = [ID_PATH + TABLE_NAME_PATH + EXTERNAL_DATABASE_TABLE])
    override fun updateExternalDatabaseTable(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String,
            @RequestBody metadataUpdate: MetadataUpdate) {
        val tableId = getExternalDatabaseObjectId(organizationId, tableName)
        ensureAdminAccess()
        edms.updateOrganizationExternalDatabaseTable(organizationId, tableId, tableName, metadataUpdate)
    }

    @Timed
    @PatchMapping(path = [ID_PATH + TABLE_NAME_PATH + COLUMN_NAME_PATH + EXTERNAL_DATABASE_TABLE])
    override fun updateExternalDatabaseColumn(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String,
            @PathVariable(COLUMN_NAME) columnName: String,
            @RequestBody metadataUpdate: MetadataUpdate) {
        val tableId = getExternalDatabaseObjectId(organizationId, tableName)
        val columnId = getExternalDatabaseObjectId(tableId, columnName)
        ensureAdminAccess()
        edms.updateOrganizationExternalDatabaseColumn(organizationId, tableId, tableName, columnId, columnName, metadataUpdate)
    }

    @Timed
    @DeleteMapping(path = [ID_PATH + TABLE_NAME_PATH + EXTERNAL_DATABASE_TABLE])
    override fun deleteExternalDatabaseTable(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String) {
        val tableFqnToId = getExternalDatabaseObjectId(organizationId, tableName)
        val aclKey = AclKey(tableFqnToId.second)
        ensureOwnerAccess(aclKey)
        ensureObjectCanBeDeleted(tableFqnToId.second)
        edms.deleteOrganizationExternalDatabaseTable(organizationId, tableFqnToId)
    }

    @Timed
    @DeleteMapping(path = [ID_PATH + EXTERNAL_DATABASE_TABLE])
    override fun deleteExternalDatabaseTables(
            @PathVariable(ID) organizationId: UUID,
            @RequestBody tableNames: Set<String>) {
        val tableIdByFqn = getExternalDatabaseObjectIds(organizationId, tableNames)
        tableIdByFqn.forEach { ensureObjectCanBeDeleted(it.value) }
        val aclKeys = tableIdByFqn.map { AclKey(it.value) }
        aclKeys.forEach { aclKey ->
            ensureOwnerAccess(aclKey)
        }
        edms.deleteOrganizationExternalDatabaseTables(organizationId, tableIdByFqn)
    }

    @Timed
    @DeleteMapping(path = [ID_PATH + TABLE_NAME_PATH + COLUMN_NAME_PATH + EXTERNAL_DATABASE_COLUMN])
    override fun deleteExternalDatabaseColumn(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String,
            @PathVariable(COLUMN_NAME) columnName: String
    ) {
        val tableFqnToId = getExternalDatabaseObjectId(organizationId, tableName)
        val columnFqnToId = getExternalDatabaseObjectId(tableFqnToId.second, columnName)
        val aclKey = AclKey(tableFqnToId.second, columnFqnToId.second)
        ensureOwnerAccess(aclKey)
        ensureObjectCanBeDeleted(columnFqnToId.second)
        edms.deleteOrganizationExternalDatabaseColumn(organizationId, tableFqnToId, columnFqnToId)
    }

    @Timed
    @DeleteMapping(path = [ID_PATH + TABLE_NAME_PATH + EXTERNAL_DATABASE_COLUMN])
    override fun deleteExternalDatabaseColumns(
            @PathVariable(ID) organizationId: UUID,
            @PathVariable(TABLE_NAME) tableName: String,
            @RequestBody columnNames: Set<String>
    ) {
        val tableFqnToId = getExternalDatabaseObjectId(organizationId, tableName)
        val columnIdByFqn = getExternalDatabaseObjectIds(tableFqnToId.second, columnNames)
        columnIdByFqn.forEach { ensureObjectCanBeDeleted(it.value) }
        val aclKeys = columnIdByFqn.map { AclKey(tableFqnToId.second, it.value) }.toSet()
        aclKeys.forEach { aclKey ->
            ensureOwnerAccess(aclKey)
        }
        edms.deleteOrganizationExternalDatabaseColumns(organizationId, mapOf(tableFqnToId to columnIdByFqn))
    }

    private fun getExternalDatabaseObjectId(containingObjectId: UUID, name: String): Pair<String, UUID> {
        val fqn = FullQualifiedName(containingObjectId.toString(), name).toString()
        val id = aclKeyReservations.getId(fqn)
        checkState(id != null, "External database object with name $name does not exist")
        return Pair(fqn, id!!)
    }

    private fun getExternalDatabaseObjectIds( containingObjectId: UUID, names: Set<String>): Map<String, UUID> {
        val fqns = names.map{FullQualifiedName(containingObjectId.toString(), it).toString()}.toSet()
        return aclKeyReservations.getIdsByFqn(fqns)
    }

    private fun validateHBAParameters(connectionType: PostgresConnectionType, ipAddress: String) {
        if (connectionType == PostgresConnectionType.LOCAL) {
            checkState(ipAddress.isEmpty(), "Local connections may not specify an IP address")
        } else {
            checkState(ipAddress.isNotEmpty(), "Host connections must specify at least one IP address")
        }
        val splitIpAddress = ipAddress.split("/")
        if (!InetAddresses.isInetAddress(splitIpAddress[0]) || splitIpAddress.size != 2) {
            throw IllegalStateException("Invalid IP address")
        }
    }

    override fun getAuthorizationManager(): AuthorizationManager {
        return authorizations
    }
}