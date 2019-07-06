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
 *
 */

package com.openlattice.datastore.apps.services;

import com.dataloom.streams.StreamUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.query.Predicates;
import com.openlattice.apps.App;
import com.openlattice.apps.AppConfig;
import com.openlattice.apps.AppConfigKey;
import com.openlattice.apps.AppType;
import com.openlattice.apps.AppTypeSetting;
import com.openlattice.apps.processors.AddAppTypesToAppProcessor;
import com.openlattice.apps.processors.RemoveAppTypesFromAppProcessor;
import com.openlattice.apps.processors.UpdateAppConfigEntitySetProcessor;
import com.openlattice.apps.processors.UpdateAppConfigPermissionsProcessor;
import com.openlattice.apps.processors.UpdateAppMetadataProcessor;
import com.openlattice.apps.processors.UpdateAppTypeMetadataProcessor;
import com.openlattice.authorization.AccessCheck;
import com.openlattice.authorization.AclKey;
import com.openlattice.authorization.AuthorizationManager;
import com.openlattice.authorization.AuthorizationQueryService;
import com.openlattice.authorization.HazelcastAclKeyReservationService;
import com.openlattice.authorization.Permission;
import com.openlattice.authorization.Principal;
import com.openlattice.authorization.PrincipalType;
import com.openlattice.authorization.util.AuthorizationUtils;
import com.openlattice.controllers.exceptions.BadRequestException;
import com.openlattice.datastore.services.EdmManager;
import com.openlattice.datastore.util.Util;
import com.openlattice.edm.EntitySet;
import com.openlattice.edm.events.AppCreatedEvent;
import com.openlattice.edm.events.AppDeletedEvent;
import com.openlattice.edm.events.AppTypeCreatedEvent;
import com.openlattice.edm.events.AppTypeDeletedEvent;
import com.openlattice.edm.requests.MetadataUpdate;
import com.openlattice.edm.set.EntitySetFlag;
import com.openlattice.hazelcast.HazelcastMap;
import com.openlattice.organization.Organization;
import com.openlattice.organization.roles.Role;
import com.openlattice.organizations.HazelcastOrganizationService;
import com.openlattice.organizations.roles.SecurePrincipalsManager;
import com.openlattice.postgres.mapstores.AppConfigMapstore;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

public class AppService {
    private final IMap<UUID, App>                    apps;
    private final IMap<UUID, AppType>                appTypes;
    private final IMap<AppConfigKey, AppTypeSetting> appConfigs;
    private final IMap<String, UUID>                 aclKeys;

    private final EdmManager                        edmService;
    private final HazelcastOrganizationService      organizationService;
    private final AuthorizationQueryService         authorizations;
    private final AuthorizationManager              authorizationService;
    private final SecurePrincipalsManager           principalsService;
    private final HazelcastAclKeyReservationService reservations;

    @Inject
    private EventBus eventBus;

    public AppService(
            HazelcastInstance hazelcast,
            EdmManager edmService,
            HazelcastOrganizationService organizationService,
            AuthorizationQueryService authorizations,
            AuthorizationManager authorizationService,
            SecurePrincipalsManager principalsService,
            HazelcastAclKeyReservationService reservations
    ) {
        this.apps = hazelcast.getMap( HazelcastMap.APPS.name() );
        this.appTypes = hazelcast.getMap( HazelcastMap.APP_TYPES.name() );
        this.appConfigs = hazelcast.getMap( HazelcastMap.APP_CONFIGS.name() );
        this.aclKeys = hazelcast.getMap( HazelcastMap.ACL_KEYS.name() );
        this.edmService = edmService;
        this.organizationService = organizationService;
        this.authorizations = authorizations;
        this.authorizationService = authorizationService;
        this.principalsService = principalsService;
        this.reservations = reservations;
    }

    public Iterable<App> getApps() {
        return apps.values();
    }

    public Iterable<AppType> getAppTypes() {
        return appTypes.values();
    }

    public UUID createApp( App app ) {
        reservations.reserveIdAndValidateType( app, app::getName );
        apps.put( app.getId(), app );
        eventBus.post( new AppCreatedEvent( app ) );
        return app.getId();
    }

    public void deleteApp( UUID appId ) {
        apps.delete( appId );
        reservations.release( appId );
        eventBus.post( new AppDeletedEvent( appId ) );
    }

    public App getApp( UUID appId ) {
        return apps.get( appId );
    }

    public App getApp( String name ) {
        UUID id = Util.getSafely( aclKeys, name );
        return getApp( id );
    }

    private String getNextAvailableName( String name ) {
        String nameAttempt = name;
        int counter = 1;
        while ( reservations.isReserved( nameAttempt ) ) {
            nameAttempt = name + "_" + String.valueOf( counter );
            counter++;
        }
        return nameAttempt;
    }

    private String formatEntitySetName( String prefix, FullQualifiedName appTypeFqn ) {
        String name = prefix + "_" + appTypeFqn.getNamespace() + "_" + appTypeFqn.getName();
        name = name.toLowerCase().replaceAll( "[^a-z0-9_]", "" );
        return getNextAvailableName( name );
    }

    private UUID generateEntitySet( UUID organizationId, UUID appTypeId, String prefix, Principal principal ) {
        AppType appType = getAppType( appTypeId );
        String name = formatEntitySetName( prefix, appType.getType() );
        String title = appType.getTitle() + " (" + prefix + ")";
        String description =
                "Auto-generated for organization" + organizationId.toString() + "\n\n" + appType.getDescription();
        EnumSet<EntitySetFlag> flags = EnumSet.noneOf( EntitySetFlag.class );

        EntitySet entitySet = new EntitySet( Optional.empty(),
                appType.getEntityTypeId(),
                name,
                title,
                Optional.of( description ),
                ImmutableSet.of(),
                Optional.empty(),
                Optional.of( organizationId ),
                Optional.of( flags ),
                Optional.of( new LinkedHashSet<>( organizationService.getDefaultPartitions( organizationId ) ) ) );
        edmService.createEntitySet( principal, entitySet );
        return entitySet.getId();
    }

    private Map<Permission, Principal> getOrCreateRolesForAppPermission(
            App app,
            UUID organizationId,
            EnumSet<Permission> permissions,
            Principal user ) {
        Map<Permission, Principal> result = Maps.newHashMap();
        permissions.forEach( permission -> {
            String title = app.getTitle().concat( " - " ).concat( permission.name() );
            Principal principal = new Principal( PrincipalType.ROLE,
                    organizationId.toString().concat( "|" ).concat( title ) );
            String description = permission.name().concat( " permission for the " ).concat( app.getTitle() )
                    .concat( " app" );
            Role role = new Role( Optional.empty(),
                    organizationId,
                    principal,
                    title,
                    Optional.of( description ) );
            try {
                principalsService.createSecurablePrincipalIfNotExists( user, role );
                result.put( permission, principal );
            } catch ( Exception e ) {
                throw new BadRequestException( "The requested app has already been installed for this organization" );
            }
        } );
        return result;
    }

    public void installApp( UUID appId, UUID organizationId, String prefix, Principal principal ) {
        App app = getApp( appId );
        Preconditions.checkNotNull( app, "The requested app with id %s does not exists.", appId.toString() );

        Map<Permission, Principal> appRoles = getOrCreateRolesForAppPermission( app,
                organizationId,
                EnumSet.of( Permission.READ, Permission.WRITE, Permission.OWNER ),
                principal );

        Principal appPrincipal = new Principal( PrincipalType.APP,
                AppConfig.getAppPrincipalId( appId, organizationId ) );

        Set<Principal> ownerPrincipals = Sets
                .newHashSet( authorizations.getOwnersForSecurableObject( new AclKey( organizationId ) ) );

        app.getAppTypeIds().stream().forEach( appTypeId -> {
            createEntitySetForApp( new AppConfigKey( appId, organizationId, appTypeId ),
                    prefix,
                    principal,
                    appPrincipal,
                    appRoles,
                    ownerPrincipals );
        } );
        organizationService.addAppToOrg( organizationId, appId );
    }

    public UUID createAppType( AppType appType ) {
        reservations.reserveIdAndValidateType( appType );
        appTypes.putIfAbsent( appType.getId(), appType );
        eventBus.post( new AppTypeCreatedEvent( appType ) );
        return appType.getId();
    }

    public void deleteAppType( UUID id ) {
        appTypes.delete( id );
        reservations.release( id );
        eventBus.post( new AppTypeDeletedEvent( id ) );
    }

    public AppType getAppType( UUID id ) {
        return appTypes.get( id );
    }

    public AppType getAppType( FullQualifiedName fqn ) {
        UUID id = Util.getSafely( aclKeys, Util.fqnToString( fqn ) );
        return getAppType( id );
    }

    public Map<UUID, AppType> getAppTypes( Set<UUID> appTypeIds ) {
        return appTypes.getAll( appTypeIds );
    }

    public List<AppConfig> getAvailableConfigs(
            UUID appId,
            Set<Principal> principals,
            Iterable<Organization> organizations ) {

        List<AppConfig> availableConfigs = Lists.newArrayList();
        App app = apps.get( appId );

        Map<UUID, Organization> orgsById = StreamUtil.stream( organizations )
                .collect( Collectors.toMap( org -> org.getId(), Function.identity() ) );

        Map<UUID, Map<AppConfigKey, AppTypeSetting>> orgsToSettings = orgsById.keySet().stream()
                .filter( orgId -> organizationService.getOrganizationApps( orgId ).contains( appId ) )
                .collect( Collectors
                        .toMap( Function.identity(), orgId -> appConfigs.getAll( app.getAppTypeIds().stream()
                                .map( id -> new AppConfigKey( appId, orgId, id ) ).collect(
                                        Collectors.toSet() ) ) ) );

        Set<AccessCheck> accessChecks = orgsToSettings.values().stream().flatMap( map -> map.values().stream() )
                .map( setting -> new AccessCheck( new AclKey( setting.getEntitySetId() ),
                        setting.getPermissions() ) ).collect( Collectors.toSet() );

        Set<Principal> allAppPrincipals = orgsToSettings.keySet().stream()
                .map( orgId -> new Principal( PrincipalType.APP,
                        AppConfig.getAppPrincipalId( app.getId(), orgId ) ) ).collect( Collectors.toSet() );

        Map<UUID, Boolean> entitySetIsAuthorized = Maps.newHashMap();

        // User permissions and app permissions must be evaluated separately, since both must have permissions
        Stream.concat( authorizationService.accessChecksForPrincipals( accessChecks, principals ),
                authorizationService.accessChecksForPrincipals( accessChecks, allAppPrincipals ) )
                .forEach( authorization -> {
                    UUID entitySetId = AuthorizationUtils.getLastAclKeySafely( authorization.getAclKey() );
                    boolean isAuthorized = !authorization.getPermissions().containsValue( false );

                    if ( !isAuthorized ) {
                        entitySetIsAuthorized.put( entitySetId, false );
                    } else if ( !entitySetIsAuthorized.containsKey( entitySetId ) ) {
                        entitySetIsAuthorized.put( entitySetId, true );
                    }
                } );

        orgsToSettings.entrySet().stream().filter( entry -> {
            for ( AppTypeSetting setting : orgsToSettings.get( entry.getKey() ).values() ) {
                if ( !entitySetIsAuthorized.get( setting.getEntitySetId() ) ) {
                    return false;
                }
            }
            return true;
        } ).forEach( entry -> {
            Map<String, AppTypeSetting> config = entry.getValue().entrySet().stream()
                    .collect( Collectors
                            .toMap( settingEntry -> appTypes.get( settingEntry.getKey().getAppTypeId() ).getType()
                                    .getFullQualifiedNameAsString(), settingEntry -> settingEntry.getValue() ) );
            AppConfig appConfig = new AppConfig( Optional.of( app.getId() ),
                    new Principal( PrincipalType.APP,
                            AppConfig.getAppPrincipalId( app.getId(), entry.getKey() ) ),
                    app.getTitle(),
                    Optional.of( app.getDescription() ),
                    app.getId(),
                    orgsById.get( entry.getKey() ),
                    config );
            availableConfigs.add( appConfig );
        } );

        return availableConfigs;
    }

    public void addAppTypesToApp( UUID appId, Set<UUID> appTypeIds ) {
        apps.executeOnKey( appId, new AddAppTypesToAppProcessor( appTypeIds ) );
        updateAppConfigsForNewAppType( appId, appTypeIds );
        eventBus.post( new AppCreatedEvent( apps.get( appId ) ) );
    }

    public void removeAppTypesFromApp( UUID appId, Set<UUID> appTypeIds ) {
        apps.executeOnKey( appId, new RemoveAppTypesFromAppProcessor( appTypeIds ) );
        eventBus.post( new AppCreatedEvent( apps.get( appId ) ) );
    }

    public void updateAppConfigEntitySetId( UUID organizationId, UUID appId, UUID appTypeId, UUID entitySetId ) {
        AppConfigKey key = new AppConfigKey( appId, organizationId, appTypeId );
        appConfigs.executeOnKey( key, new UpdateAppConfigEntitySetProcessor( entitySetId ) );

        Principal appPrincipal = new Principal( PrincipalType.APP,
                AppConfig.getAppPrincipalId( appId, organizationId ) );
        EnumSet<Permission> permissions = appConfigs.get( key ).getPermissions();
        authorizationService.addPermission( new AclKey( entitySetId ), appPrincipal, permissions );
    }

    public void updateAppConfigPermissions(
            UUID organizationId,
            UUID appId,
            UUID appTypeId,
            EnumSet<Permission> permissions ) {
        AppConfigKey key = new AppConfigKey( appId, organizationId, appTypeId );
        appConfigs.executeOnKey( key, new UpdateAppConfigPermissionsProcessor( permissions ) );

        Principal appPrincipal = new Principal( PrincipalType.APP,
                AppConfig.getAppPrincipalId( appId, organizationId ) );
        UUID entitySetId = appConfigs.get( key ).getEntitySetId();
        authorizationService.addPermission( new AclKey( entitySetId ), appPrincipal, permissions );
    }

    public void updateAppMetadata( UUID appId, MetadataUpdate metadataUpdate ) {
        apps.executeOnKey( appId, new UpdateAppMetadataProcessor( metadataUpdate ) );
        eventBus.post( new AppCreatedEvent( apps.get( appId ) ) );
    }

    public void updateAppTypeMetadata( UUID appTypeId, MetadataUpdate metadataUpdate ) {
        appTypes.executeOnKey( appTypeId, new UpdateAppTypeMetadataProcessor( metadataUpdate ) );
        eventBus.post( new AppTypeCreatedEvent( appTypes.get( appTypeId ) ) );
    }

    private void createEntitySetForApp(
            AppConfigKey key,
            String prefix,
            Principal userPrincipal,
            Principal appPrincipal,
            Map<Permission, Principal> appRoles,
            Set<Principal> owners ) {

        EnumSet<Permission> allPermissions = EnumSet
                .of( Permission.DISCOVER, Permission.LINK, Permission.READ, Permission.WRITE, Permission.OWNER );

        UUID entitySetId = generateEntitySet( key.getOrganizationId(), key.getAppTypeId(), prefix, userPrincipal );
        appConfigs.put( key, new AppTypeSetting( entitySetId, EnumSet.of( Permission.READ, Permission.WRITE ) ) );
        authorizationService.addPermission( new AclKey( entitySetId ), appPrincipal, allPermissions );
        owners.forEach( owner -> {
            authorizationService.addPermission( new AclKey( entitySetId ), owner, allPermissions );
        } );

        edmService.getEntityType( appTypes.get( key.getAppTypeId() ).getEntityTypeId() ).getProperties()
                .forEach( propertyTypeId -> {
                    AclKey aclKeys = new AclKey( entitySetId, propertyTypeId );
                    appRoles.entrySet().forEach( entry -> {
                        Permission permission = entry.getKey();
                        Principal rolePrincipal = entry.getValue();
                        authorizationService.addPermission( aclKeys, rolePrincipal, EnumSet.of( permission ) );
                    } );

                    owners.forEach( owner -> {
                        authorizationService.addPermission( aclKeys, owner, allPermissions );
                    } );

                    authorizationService.addPermission( aclKeys, appPrincipal, allPermissions );
                } );

        appRoles.entrySet().forEach( entry -> {
            Permission permission = entry.getKey();
            Principal rolePrincipal = entry.getValue();
            authorizationService
                    .addPermission( new AclKey( entitySetId ), rolePrincipal, EnumSet.of( permission ) );
            edmService.getEntityType( appTypes.get( key.getAppTypeId() ).getEntityTypeId() ).getProperties()
                    .forEach( propertyTypeId -> {
                        AclKey aclKeys = new AclKey( entitySetId, propertyTypeId );
                        authorizationService.addPermission( aclKeys, appPrincipal, allPermissions );
                        authorizationService.addPermission( aclKeys, rolePrincipal, EnumSet.of( permission ) );
                    } );
        } );
    }

    private void updateAppConfigsForNewAppType( UUID appId, Set<UUID> appTypeIds ) {
        Set<AppConfigKey> appConfigKeys = appConfigs.keySet( Predicates.equal( AppConfigMapstore.APP_ID, appId ) );
        appConfigKeys.stream().map( key -> key.getOrganizationId() ).distinct().forEach( organizationId -> {
            Set<Principal> ownerPrincipals = Sets
                    .newHashSet( authorizations.getOwnersForSecurableObject( new AclKey( organizationId ) ) );
            Principal appPrincipal = new Principal( PrincipalType.APP,
                    AppConfig.getAppPrincipalId( appId, organizationId ) );
            Organization org = organizationService.getOrganization( organizationId );
            Map<Permission, Principal> appRoles = getOrCreateRolesForAppPermission( getApp( appId ),
                    organizationId,
                    EnumSet.of( Permission.READ, Permission.WRITE, Permission.OWNER ),
                    ownerPrincipals.iterator().next() );

            appTypeIds.forEach( appTypeId -> {
                AppConfigKey appConfigKey = new AppConfigKey( appId, organizationId, appTypeId );
                if ( !appConfigKeys.contains( appConfigKey ) ) {
                    createEntitySetForApp( appConfigKey,
                            org.getTitle(),
                            ownerPrincipals.stream()
                                    .filter( principal -> principal.getType().equals( PrincipalType.USER ) ).iterator()
                                    .next(),
                            appPrincipal,
                            appRoles,
                            ownerPrincipals );
                }
            } );
        } );
    }
}
