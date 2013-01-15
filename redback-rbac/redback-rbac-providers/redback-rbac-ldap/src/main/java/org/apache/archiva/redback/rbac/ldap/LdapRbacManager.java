package org.apache.archiva.redback.rbac.ldap;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.redback.common.ldap.MappingException;
import org.apache.archiva.redback.common.ldap.connection.LdapConnection;
import org.apache.archiva.redback.common.ldap.connection.LdapConnectionFactory;
import org.apache.archiva.redback.common.ldap.connection.LdapException;
import org.apache.archiva.redback.common.ldap.role.LdapRoleMapper;
import org.apache.archiva.redback.components.cache.Cache;
import org.apache.archiva.redback.configuration.UserConfiguration;
import org.apache.archiva.redback.configuration.UserConfigurationKeys;
import org.apache.archiva.redback.rbac.AbstractRole;
import org.apache.archiva.redback.rbac.Operation;
import org.apache.archiva.redback.rbac.Permission;
import org.apache.archiva.redback.rbac.RBACManager;
import org.apache.archiva.redback.rbac.RBACManagerListener;
import org.apache.archiva.redback.rbac.RbacManagerException;
import org.apache.archiva.redback.rbac.RbacObjectInvalidException;
import org.apache.archiva.redback.rbac.RbacObjectNotFoundException;
import org.apache.archiva.redback.rbac.Resource;
import org.apache.archiva.redback.rbac.Role;
import org.apache.archiva.redback.rbac.UserAssignment;
import org.apache.archiva.redback.users.User;
import org.apache.archiva.redback.users.UserManager;
import org.apache.archiva.redback.users.UserManagerException;
import org.apache.archiva.redback.users.ldap.ctl.LdapController;
import org.apache.archiva.redback.users.ldap.ctl.LdapControllerException;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LdapRbacManager will read datas from ldap for mapping groups to role.
 * Write operations will delegate to cached implementation.
 *
 * @author Olivier Lamy
 */
@Service( "rbacManager#ldap" )
public class LdapRbacManager
    implements RBACManager, RBACManagerListener
{

    private Logger log = LoggerFactory.getLogger( getClass() );

    @Inject
    @Named( value = "rbacManager#cached" )
    private RBACManager rbacImpl;

    @Inject
    @Named( value = "ldapRoleMapper#default" )
    private LdapRoleMapper ldapRoleMapper;

    @Inject
    @Named( value = "userConfiguration#default" )
    private UserConfiguration userConf;

    @Inject
    @Named( value = "userManager#ldap" )
    private UserManager userManager;

    @Inject
    private LdapConnectionFactory ldapConnectionFactory;

    @Inject
    private LdapController ldapController;

    private boolean writableLdap = false;

    @PostConstruct
    public void initialize()
    {
        this.writableLdap = userConf.getBoolean( UserConfigurationKeys.LDAP_WRITABLE, this.writableLdap );
    }


    public void addChildRole( Role role, Role childRole )
        throws RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.addChildRole( role, childRole );
    }

    public void addListener( RBACManagerListener listener )
    {
        this.rbacImpl.addListener( listener );
    }

    public Operation createOperation( String name )
        throws RbacManagerException
    {
        return this.rbacImpl.createOperation( name );
    }

    public Permission createPermission( String name )
        throws RbacManagerException
    {
        return this.rbacImpl.createPermission( name );
    }

    public Permission createPermission( String name, String operationName, String resourceIdentifier )
        throws RbacManagerException
    {
        return this.rbacImpl.createPermission( name, operationName, resourceIdentifier );
    }

    public Resource createResource( String identifier )
        throws RbacManagerException
    {
        return this.rbacImpl.createResource( identifier );
    }

    public Role createRole( String name )
    {
        return this.rbacImpl.createRole( name );
    }

    public UserAssignment createUserAssignment( String username )
        throws RbacManagerException
    {
        // TODO ldap cannot or isWritable ldap ?
        return this.rbacImpl.createUserAssignment( username );
    }

    public void eraseDatabase()
    {
        if ( writableLdap )
        {
            try
            {
                ldapRoleMapper.removeAllRoles();
            }
            catch ( MappingException e )
            {
                log.warn( "skip error removing all roles {}", e.getMessage() );
            }
        }
        this.rbacImpl.eraseDatabase();
    }

    /**
     * @see org.apache.archiva.redback.rbac.RBACManager#getAllAssignableRoles()
     */
    public List<Role> getAllAssignableRoles()
        throws RbacManagerException, RbacObjectNotFoundException
    {
        try
        {
            Collection<String> roleNames = ldapRoleMapper.getLdapGroupMappings().values();

            List<Role> roles = new ArrayList<Role>();

            for ( String name : roleNames )
            {
                roles.add( new RoleImpl( name ) );
            }

            return roles;
        }
        catch ( MappingException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
    }

    public List<Operation> getAllOperations()
        throws RbacManagerException
    {
        return this.rbacImpl.getAllOperations();
    }

    public List<Permission> getAllPermissions()
        throws RbacManagerException
    {
        return this.rbacImpl.getAllPermissions();
    }

    public List<Resource> getAllResources()
        throws RbacManagerException
    {
        return this.rbacImpl.getAllResources();
    }

    public List<Role> getAllRoles()
        throws RbacManagerException
    {
        try
        {
            List<String> groups = ldapRoleMapper.getAllGroups();
            return mapToRoles( groups );
        }
        catch ( MappingException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
        //return this.rbacImpl.getAllRoles();
    }

    public List<UserAssignment> getAllUserAssignments()
        throws RbacManagerException
    {
        // TODO FROM ldap or from real impl ?
        //return this.rbacImpl.getAllUserAssignments();
        LdapConnection ldapConnection = null;
        try
        {
            ldapConnection = ldapConnectionFactory.getConnection();
            Map<String, Collection<String>> usersWithRoles =
                ldapController.findUsersWithRoles( ldapConnection.getDirContext() );
            List<UserAssignment> userAssignments = new ArrayList<UserAssignment>( usersWithRoles.size() );

            for ( Map.Entry<String, Collection<String>> entry : usersWithRoles.entrySet() )
            {
                UserAssignment userAssignment = new UserAssignmentImpl( entry.getKey(), entry.getValue() );
                userAssignments.add( userAssignment );
            }

            return userAssignments;
        }
        catch ( LdapControllerException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
        catch ( LdapException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
        finally
        {
            if ( ldapConnection != null )
            {
                ldapConnection.close();
            }
        }
    }

    public Map<String, List<Permission>> getAssignedPermissionMap( String username )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        // TODO here !!
        return this.rbacImpl.getAssignedPermissionMap( username );
    }

    public Set<Permission> getAssignedPermissions( String username )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        // TODO here !!
        return this.rbacImpl.getAssignedPermissions( username );
    }

    private List<Role> mapToRoles( List<String> groups )
        throws MappingException, RbacManagerException
    {
        if ( groups == null || groups.isEmpty() )
        {
            return Collections.emptyList();
        }

        List<Role> roles = new ArrayList<Role>( groups.size() );
        Map<String, String> mappedGroups = ldapRoleMapper.getLdapGroupMappings();
        for ( String group : groups )
        {
            String roleName = mappedGroups.get( group );
            if ( roleName != null )
            {
                Role role = getRole( roleName );
                if ( role != null )
                {
                    roles.add( role );
                }
            }
        }
        return roles;

    }

    public Collection<Role> getAssignedRoles( String username )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        try
        {
            // TODO here !!
            List<String> roleNames = ldapRoleMapper.getRoles( username );

            if ( roleNames.isEmpty() )
            {
                return Collections.emptyList();
            }

            List<Role> roles = new ArrayList<Role>( roleNames.size() );

            for ( String name : roleNames )
            {
                roles.add( new RoleImpl( name ) );
            }

            return roles;
        }
        catch ( MappingException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
    }

    public Collection<Role> getAssignedRoles( UserAssignment userAssignment )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return getAssignedRoles( userAssignment.getPrincipal() );
        //return this.rbacImpl.getAssignedRoles( userAssignment );
    }

    public Map<String, Role> getChildRoles( Role role )
        throws RbacManagerException
    {
        return this.rbacImpl.getChildRoles( role );
    }

    public Map<String, Role> getParentRoles( Role role )
        throws RbacManagerException
    {
        return this.rbacImpl.getParentRoles( role );
    }

    public Collection<Role> getEffectivelyAssignedRoles( String username )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        // TODO here !!
        return this.rbacImpl.getEffectivelyAssignedRoles( username );
    }

    public Collection<Role> getEffectivelyUnassignedRoles( String username )
        throws RbacManagerException, RbacObjectNotFoundException
    {
        // TODO here !!
        return this.rbacImpl.getEffectivelyUnassignedRoles( username );
    }

    public Set<Role> getEffectiveRoles( Role role )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return this.rbacImpl.getEffectiveRoles( role );
    }

    public Resource getGlobalResource()
        throws RbacManagerException
    {
        return this.rbacImpl.getGlobalResource();
    }

    public Operation getOperation( String operationName )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return this.rbacImpl.getOperation( operationName );
    }

    public Permission getPermission( String permissionName )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return this.rbacImpl.getPermission( permissionName );
    }

    public Resource getResource( String resourceIdentifier )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return this.rbacImpl.getResource( resourceIdentifier );
    }

    public Role getRole( String roleName )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return this.rbacImpl.getRole( roleName );
    }

    public Map<String, Role> getRoles( Collection<String> roleNames )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        return this.rbacImpl.getRoles( roleNames );
    }

    public Collection<Role> getUnassignedRoles( String username )
        throws RbacManagerException, RbacObjectNotFoundException
    {
        // TODO here !!
        return this.rbacImpl.getUnassignedRoles( username );
    }

    public UserAssignment getUserAssignment( String username )
        throws RbacObjectNotFoundException, RbacManagerException
    {
        // TODO here !!
        return this.rbacImpl.getUserAssignment( username );
    }

    public List<UserAssignment> getUserAssignmentsForRoles( Collection<String> roleNames )
        throws RbacManagerException
    {
        // TODO from ldap
        return this.rbacImpl.getUserAssignmentsForRoles( roleNames );
    }

    public boolean operationExists( Operation operation )
    {
        return this.rbacImpl.operationExists( operation );
    }

    public boolean operationExists( String name )
    {
        return this.rbacImpl.operationExists( name );
    }

    public boolean permissionExists( Permission permission )
    {
        return this.rbacImpl.permissionExists( permission );
    }

    public boolean permissionExists( String name )
    {
        return this.rbacImpl.permissionExists( name );
    }

    public void rbacInit( boolean freshdb )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacInit( freshdb );
        }
    }

    public void rbacPermissionRemoved( Permission permission )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacPermissionRemoved( permission );
        }

    }

    public void rbacPermissionSaved( Permission permission )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacPermissionSaved( permission );
        }

    }

    public void rbacRoleRemoved( Role role )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacRoleRemoved( role );
        }

    }

    public void rbacRoleSaved( Role role )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacRoleSaved( role );
        }

    }

    public void rbacUserAssignmentRemoved( UserAssignment userAssignment )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacUserAssignmentRemoved( userAssignment );
        }

    }

    public void rbacUserAssignmentSaved( UserAssignment userAssignment )
    {
        if ( rbacImpl instanceof RBACManagerListener )
        {
            ( (RBACManagerListener) this.rbacImpl ).rbacUserAssignmentSaved( userAssignment );
        }

    }

    public void removeListener( RBACManagerListener listener )
    {
        this.rbacImpl.removeListener( listener );
    }

    public void removeOperation( Operation operation )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removeOperation( operation );
    }

    public void removeOperation( String operationName )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removeOperation( operationName );
    }

    public void removePermission( Permission permission )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removePermission( permission );
    }

    public void removePermission( String permissionName )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removePermission( permissionName );
    }

    public void removeResource( Resource resource )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removeResource( resource );
    }

    public void removeResource( String resourceIdentifier )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removeResource( resourceIdentifier );
    }

    public void removeRole( Role role )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removeRole( role );
    }

    public void removeRole( String roleName )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        this.rbacImpl.removeRole( roleName );
    }

    public void removeUserAssignment( String username )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        // TODO ldap cannot or isWritable ldap ?
        this.rbacImpl.removeUserAssignment( username );
    }

    public void removeUserAssignment( UserAssignment userAssignment )
        throws RbacObjectNotFoundException, RbacObjectInvalidException, RbacManagerException
    {
        // TODO ldap cannot or isWritable ldap ?
        this.rbacImpl.removeUserAssignment( userAssignment );
    }

    public boolean resourceExists( Resource resource )
    {
        return this.rbacImpl.resourceExists( resource );
    }

    public boolean resourceExists( String identifier )
    {
        return this.rbacImpl.resourceExists( identifier );
    }

    public boolean roleExists( Role role )
        throws RbacManagerException
    {
        if ( role == null )
        {
            return false;
        }
        return roleExists( role.getName() );
    }

    public boolean roleExists( String name )
        throws RbacManagerException
    {
        if ( StringUtils.isEmpty( name ) )
        {
            return false;
        }
        try
        {
            return ldapRoleMapper.getAllRoles().contains( name );
        }
        catch ( Exception e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
    }

    public Operation saveOperation( Operation operation )
        throws RbacObjectInvalidException, RbacManagerException
    {
        return this.rbacImpl.saveOperation( operation );
    }

    public Permission savePermission( Permission permission )
        throws RbacObjectInvalidException, RbacManagerException
    {
        return this.rbacImpl.savePermission( permission );
    }

    public Resource saveResource( Resource resource )
        throws RbacObjectInvalidException, RbacManagerException
    {
        return this.rbacImpl.saveResource( resource );
    }

    public synchronized Role saveRole( Role role )
        throws RbacObjectInvalidException, RbacManagerException
    {
        if ( writableLdap )
        {
            try
            {
                ldapRoleMapper.saveRole( role.getName() );
            }
            catch ( MappingException e )
            {
                throw new RbacManagerException( e.getMessage(), e );
            }
        }
        return this.rbacImpl.saveRole( role );
    }

    public synchronized void saveRoles( Collection<Role> roles )
        throws RbacObjectInvalidException, RbacManagerException
    {
        if ( writableLdap )
        {
            try
            {
                for ( Role role : roles )
                {
                    ldapRoleMapper.saveRole( role.getName() );
                }
            }
            catch ( MappingException e )
            {
                throw new RbacManagerException( e.getMessage(), e );
            }
        }
        this.rbacImpl.saveRoles( roles );
    }

    public UserAssignment saveUserAssignment( UserAssignment userAssignment )
        throws RbacObjectInvalidException, RbacManagerException
    {
        try
        {
            if ( !userManager.userExists( userAssignment.getPrincipal() ) )
            {
                User user = userManager.createUser( userAssignment.getPrincipal(), null, null );
                user = userManager.addUser( user );
            }

            List<String> allRoles = ldapRoleMapper.getAllRoles();

            List<String> currentUserRoles = ldapRoleMapper.getRoles( userAssignment.getPrincipal() );

            for ( String role : userAssignment.getRoleNames() )
            {
                if ( !currentUserRoles.contains( role ) && writableLdap )
                {
                    // role exists in ldap ?
                    if ( !allRoles.contains( role ) )
                    {
                        ldapRoleMapper.saveRole( role );
                    }
                    ldapRoleMapper.saveUserRole( role, userAssignment.getPrincipal() );
                }

            }

            return userAssignment;
        }
        catch ( UserManagerException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }
        catch ( MappingException e )
        {
            throw new RbacManagerException( e.getMessage(), e );
        }

        //return this.rbacImpl.saveUserAssignment( userAssignment );
    }

    public boolean userAssignmentExists( String principal )
    {
        // TODO here
        return this.rbacImpl.userAssignmentExists( principal );
    }

    public boolean userAssignmentExists( UserAssignment assignment )
    {
        // TODO here
        return this.rbacImpl.userAssignmentExists( assignment );
    }

    public RBACManager getRbacImpl()
    {
        return rbacImpl;
    }

    public void setRbacImpl( RBACManager rbacImpl )
    {
        this.rbacImpl = rbacImpl;
    }

    public boolean isWritableLdap()
    {
        return writableLdap;
    }

    public void setWritableLdap( boolean writableLdap )
    {
        this.writableLdap = writableLdap;
    }

    public LdapRoleMapper getLdapRoleMapper()
    {
        return ldapRoleMapper;
    }

    public void setLdapRoleMapper( LdapRoleMapper ldapRoleMapper )
    {
        this.ldapRoleMapper = ldapRoleMapper;
    }

    private static class RoleImpl
        extends AbstractRole
    {
        private String name;

        private RoleImpl( String name )
        {
            this.name = name;
        }

        public void addPermission( Permission permission )
        {
            // no op
        }

        public void addChildRoleName( String name )
        {
            // no op
        }

        public List<String> getChildRoleNames()
        {
            return Collections.emptyList();
        }

        public String getDescription()
        {
            return null;
        }

        public String getName()
        {
            return this.name;
        }

        public List<Permission> getPermissions()
        {
            return Collections.emptyList();
        }

        public boolean isAssignable()
        {
            return true;
        }

        public void removePermission( Permission permission )
        {
            // no op
        }

        public void setAssignable( boolean assignable )
        {
            // no op
        }

        public void setChildRoleNames( List<String> names )
        {
            // no op
        }

        public void setDescription( String description )
        {
            // no op
        }

        public void setName( String name )
        {
            this.name = name;
        }

        public void setPermissions( List<Permission> permissions )
        {
            // no op
        }

        public boolean isPermanent()
        {
            return true;
        }

        public void setPermanent( boolean permanent )
        {
            // no op
        }
    }

    private static class UserAssignmentImpl
        implements UserAssignment
    {
        private String username;

        private List<String> roleNames;

        private boolean permanent;

        private UserAssignmentImpl( String username, Collection<String> roleNames )
        {
            this.username = username;

            if ( roleNames == null )
            {
                this.roleNames = new ArrayList<String>();
            }
            else
            {
                this.roleNames = new ArrayList<String>( roleNames );
            }
        }

        public String getPrincipal()
        {
            return this.username;
        }

        public List<String> getRoleNames()
        {
            return this.roleNames;
        }

        public void addRoleName( Role role )
        {
            if ( role == null )
            {
                return;
            }
            this.roleNames.add( role.getName() );
        }

        public void addRoleName( String roleName )
        {
            if ( roleName == null )
            {
                return;
            }
            this.roleNames.add( roleName );
        }

        public void removeRoleName( Role role )
        {
            if ( role == null )
            {
                return;
            }
            this.roleNames.remove( role.getName() );
        }

        public void removeRoleName( String roleName )
        {
            if ( roleName == null )
            {
                return;
            }
            this.roleNames.remove( roleName );
        }

        public void setPrincipal( String principal )
        {
            this.username = principal;
        }

        public void setRoleNames( List<String> roles )
        {
            this.roleNames = roles;
        }

        public boolean isPermanent()
        {
            return this.permanent;
        }

        public void setPermanent( boolean permanent )
        {
            this.permanent = permanent;
        }
    }
}