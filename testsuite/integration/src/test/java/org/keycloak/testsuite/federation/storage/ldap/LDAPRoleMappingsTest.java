/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.federation.storage.ldap;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runners.MethodSorters;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.LDAPStorageProviderFactory;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.mappers.membership.LDAPGroupMapperMode;
import org.keycloak.models.AccountRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.storage.ldap.mappers.membership.role.RoleLDAPStorageMapper;
import org.keycloak.testsuite.OAuthClient;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.rule.KeycloakRule;
import org.keycloak.testsuite.rule.LDAPRule;
import org.keycloak.testsuite.rule.WebResource;
import org.keycloak.testsuite.rule.WebRule;
import org.openqa.selenium.WebDriver;

import java.util.Set;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LDAPRoleMappingsTest {

    private static LDAPRule ldapRule = new LDAPRule();

    private static ComponentModel ldapModel = null;

    private static KeycloakRule keycloakRule = new KeycloakRule(new KeycloakRule.KeycloakSetup() {

        @Override
        public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
            LDAPTestUtils.addLocalUser(manager.getSession(), appRealm, "mary", "mary@test.com", "password-app");

            MultivaluedHashMap<String,String> ldapConfig = LDAPTestUtils.getLdapRuleConfig(ldapRule);
            ldapConfig.putSingle(LDAPConstants.SYNC_REGISTRATIONS, "true");
            ldapConfig.putSingle(LDAPConstants.EDIT_MODE, UserStorageProvider.EditMode.WRITABLE.toString());
            UserStorageProviderModel model = new UserStorageProviderModel();
            model.setLastSync(0);
            model.setChangedSyncPeriod(-1);
            model.setFullSyncPeriod(-1);
            model.setName("test-ldap");
            model.setPriority(0);
            model.setProviderId(LDAPStorageProviderFactory.PROVIDER_NAME);
            model.setConfig(ldapConfig);

            ldapModel = appRealm.addComponentModel(model);

            // Delete all LDAP users
            LDAPStorageProvider ldapFedProvider = LDAPTestUtils.getLdapProvider(session, ldapModel);
            LDAPTestUtils.removeAllLDAPUsers(ldapFedProvider, appRealm);

            // Add sample application
            ClientModel finance = appRealm.addClient("finance");

            // Delete all LDAP roles
            LDAPTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, LDAPGroupMapperMode.LDAP_ONLY);
            LDAPTestUtils.removeAllLDAPRoles(manager.getSession(), appRealm, ldapModel, "realmRolesMapper");
            LDAPTestUtils.removeAllLDAPRoles(manager.getSession(), appRealm, ldapModel, "financeRolesMapper");

            // Add some users for testing
            LDAPObject john = LDAPTestUtils.addLDAPUser(ldapFedProvider, appRealm, "johnkeycloak", "John", "Doe", "john@email.org", null, "1234");
            LDAPTestUtils.updateLDAPPassword(ldapFedProvider, john, "Password1");

            LDAPObject mary = LDAPTestUtils.addLDAPUser(ldapFedProvider, appRealm, "marykeycloak", "Mary", "Kelly", "mary@email.org", null, "5678");
            LDAPTestUtils.updateLDAPPassword(ldapFedProvider, mary, "Password1");

            LDAPObject rob = LDAPTestUtils.addLDAPUser(ldapFedProvider, appRealm, "robkeycloak", "Rob", "Brown", "rob@email.org", null, "8910");
            LDAPTestUtils.updateLDAPPassword(ldapFedProvider, rob, "Password1");

            // Add some roles for testing
            LDAPTestUtils.createLDAPRole(manager.getSession(), appRealm, ldapModel, "realmRolesMapper", "realmRole1");
            LDAPTestUtils.createLDAPRole(manager.getSession(), appRealm, ldapModel, "realmRolesMapper", "realmRole2");
            LDAPTestUtils.createLDAPRole(manager.getSession(), appRealm, ldapModel, "financeRolesMapper", "financeRole1");

            // Sync LDAP roles to Keycloak DB
            LDAPTestUtils.syncRolesFromLDAP(appRealm, ldapFedProvider, ldapModel);
        }
    });

    @ClassRule
    public static TestRule chain = RuleChain
            .outerRule(ldapRule)
            .around(keycloakRule);

    @Rule
    public WebRule webRule = new WebRule(this);

    @WebResource
    protected OAuthClient oauth;

    @WebResource
    protected WebDriver driver;

    @WebResource
    protected AppPage appPage;

    @WebResource
    protected LoginPage loginPage;

    @Test
    public void test01_ldapOnlyRoleMappings() {
        KeycloakSession session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");

            LDAPTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, LDAPGroupMapperMode.LDAP_ONLY);

            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);
            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            // 1 - Grant some roles in LDAP

            // This role should already exists as it was imported from LDAP
            RoleModel realmRole1 = appRealm.getRole("realmRole1");
            john.grantRole(realmRole1);

            // This role should already exists as it was imported from LDAP
            RoleModel realmRole2 = appRealm.getRole("realmRole2");
            mary.grantRole(realmRole2);

            // This role may already exists from previous test (was imported from LDAP), but may not
            RoleModel realmRole3 = appRealm.getRole("realmRole3");
            if (realmRole3 == null) {
                realmRole3 = appRealm.addRole("realmRole3");
            }

            john.grantRole(realmRole3);
            mary.grantRole(realmRole3);

            ClientModel accountApp = appRealm.getClientByClientId(Constants.ACCOUNT_MANAGEMENT_CLIENT_ID);
            ClientModel financeApp = appRealm.getClientByClientId("finance");

            RoleModel manageAccountRole = accountApp.getRole(AccountRoles.MANAGE_ACCOUNT);
            RoleModel financeRole1 = financeApp.getRole("financeRole1");
            john.grantRole(financeRole1);

            // 2 - Check that role mappings are not in local Keycloak DB (They are in LDAP).

            UserModel johnDb = session.userLocalStorage().getUserByUsername("johnkeycloak", appRealm);
            Set<RoleModel> johnDbRoles = johnDb.getRoleMappings();
            Assert.assertFalse(johnDbRoles.contains(realmRole1));
            Assert.assertFalse(johnDbRoles.contains(realmRole2));
            Assert.assertFalse(johnDbRoles.contains(realmRole3));
            Assert.assertFalse(johnDbRoles.contains(financeRole1));
            Assert.assertTrue(johnDbRoles.contains(manageAccountRole));

            // 3 - Check that role mappings are in LDAP and hence available through federation

            Set<RoleModel> johnRoles = john.getRoleMappings();
            Assert.assertTrue(johnRoles.contains(realmRole1));
            Assert.assertFalse(johnRoles.contains(realmRole2));
            Assert.assertTrue(johnRoles.contains(realmRole3));
            Assert.assertTrue(johnRoles.contains(financeRole1));
            Assert.assertTrue(johnRoles.contains(manageAccountRole));

            Set<RoleModel> johnRealmRoles = john.getRealmRoleMappings();
            Assert.assertEquals(2, johnRealmRoles.size());
            Assert.assertTrue(johnRealmRoles.contains(realmRole1));
            Assert.assertTrue(johnRealmRoles.contains(realmRole3));

            // account roles are not mapped in LDAP. Those are in Keycloak DB
            Set<RoleModel> johnAccountRoles = john.getClientRoleMappings(accountApp);
            Assert.assertTrue(johnAccountRoles.contains(manageAccountRole));

            Set<RoleModel> johnFinanceRoles = john.getClientRoleMappings(financeApp);
            Assert.assertEquals(1, johnFinanceRoles.size());
            Assert.assertTrue(johnFinanceRoles.contains(financeRole1));

            // 4 - Delete some role mappings and check they are deleted

            john.deleteRoleMapping(realmRole3);
            john.deleteRoleMapping(realmRole1);
            john.deleteRoleMapping(financeRole1);
            john.deleteRoleMapping(manageAccountRole);

            johnRoles = john.getRoleMappings();
            Assert.assertFalse(johnRoles.contains(realmRole1));
            Assert.assertFalse(johnRoles.contains(realmRole2));
            Assert.assertFalse(johnRoles.contains(realmRole3));
            Assert.assertFalse(johnRoles.contains(financeRole1));
            Assert.assertFalse(johnRoles.contains(manageAccountRole));

            // Cleanup
            mary.deleteRoleMapping(realmRole2);
            mary.deleteRoleMapping(realmRole3);
            john.grantRole(manageAccountRole);
        } finally {
            keycloakRule.stopSession(session, false);
        }
    }

    @Test
    public void test02_readOnlyRoleMappings() {
        KeycloakSession session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");

            LDAPTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, LDAPGroupMapperMode.READ_ONLY);

            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            RoleModel realmRole1 = appRealm.getRole("realmRole1");
            RoleModel realmRole2 = appRealm.getRole("realmRole2");
            RoleModel realmRole3 = appRealm.getRole("realmRole3");
            if (realmRole3 == null) {
                realmRole3 = appRealm.addRole("realmRole3");
            }

            // Add some role mappings directly into LDAP
            ComponentModel roleMapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ldapModel, "realmRolesMapper");
            LDAPStorageProvider ldapProvider = LDAPTestUtils.getLdapProvider(session, ldapModel);
            RoleLDAPStorageMapper roleMapper = LDAPTestUtils.getRoleMapper(roleMapperModel, ldapProvider, appRealm);

            LDAPObject maryLdap = ldapProvider.loadLDAPUserByUsername(appRealm, "marykeycloak");
            roleMapper.addRoleMappingInLDAP("realmRole1", maryLdap);
            roleMapper.addRoleMappingInLDAP("realmRole2", maryLdap);

            // Add some role to model
            mary.grantRole(realmRole3);

            // Assert that mary has both LDAP and DB mapped roles
            Set<RoleModel> maryRoles = mary.getRealmRoleMappings();
            Assert.assertTrue(maryRoles.contains(realmRole1));
            Assert.assertTrue(maryRoles.contains(realmRole2));
            Assert.assertTrue(maryRoles.contains(realmRole3));

            // Assert that access through DB will have just DB mapped role
            UserModel maryDB = session.userLocalStorage().getUserByUsername("marykeycloak", appRealm);
            Set<RoleModel> maryDBRoles = maryDB.getRealmRoleMappings();
            Assert.assertFalse(maryDBRoles.contains(realmRole1));
            Assert.assertFalse(maryDBRoles.contains(realmRole2));
            Assert.assertTrue(maryDBRoles.contains(realmRole3));

            mary.deleteRoleMapping(realmRole3);
            try {
                mary.deleteRoleMapping(realmRole1);
                Assert.fail("It wasn't expected to successfully delete LDAP role mappings in READ_ONLY mode");
            } catch (ModelException expected) {
            }

            // Delete role mappings directly in LDAP
            deleteRoleMappingsInLDAP(roleMapper, maryLdap, "realmRole1");
            deleteRoleMappingsInLDAP(roleMapper, maryLdap, "realmRole2");
        } finally {
            keycloakRule.stopSession(session, false);
        }

        session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");
            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            // Assert role mappings is not available
            Set<RoleModel> maryRoles = mary.getRealmRoleMappings();
            Assert.assertFalse(maryRoles.contains(appRealm.getRole("realmRole1")));
            Assert.assertFalse(maryRoles.contains(appRealm.getRole("realmRole2")));
            Assert.assertFalse(maryRoles.contains(appRealm.getRole("realmRole3")));
        } finally {
            keycloakRule.stopSession(session, false);
        }
    }

    @Test
    public void test03_importRoleMappings() {
        KeycloakSession session = keycloakRule.startSession();
        try {
            RealmModel appRealm = session.realms().getRealmByName("test");

            LDAPTestUtils.addOrUpdateRoleLDAPMappers(appRealm, ldapModel, LDAPGroupMapperMode.IMPORT);

            // Add some role mappings directly in LDAP
            ComponentModel roleMapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ldapModel, "realmRolesMapper");
            LDAPStorageProvider ldapProvider = LDAPTestUtils.getLdapProvider(session, ldapModel);
            RoleLDAPStorageMapper roleMapper = LDAPTestUtils.getRoleMapper(roleMapperModel, ldapProvider, appRealm);

            LDAPObject robLdap = ldapProvider.loadLDAPUserByUsername(appRealm, "robkeycloak");
            roleMapper.addRoleMappingInLDAP("realmRole1", robLdap);
            roleMapper.addRoleMappingInLDAP("realmRole2", robLdap);

            // Get user and check that he has requested roles from LDAP
            UserModel rob = session.users().getUserByUsername("robkeycloak", appRealm);
            RoleModel realmRole1 = appRealm.getRole("realmRole1");
            RoleModel realmRole2 = appRealm.getRole("realmRole2");
            RoleModel realmRole3 = appRealm.getRole("realmRole3");
            if (realmRole3 == null) {
                realmRole3 = appRealm.addRole("realmRole3");
            }
            Set<RoleModel> robRoles = rob.getRealmRoleMappings();
            Assert.assertTrue(robRoles.contains(realmRole1));
            Assert.assertTrue(robRoles.contains(realmRole2));
            Assert.assertFalse(robRoles.contains(realmRole3));

            // Add some role mappings in model and check that user has it
            rob.grantRole(realmRole3);
            robRoles = rob.getRealmRoleMappings();
            Assert.assertTrue(robRoles.contains(realmRole3));

            // Delete some role mappings in LDAP and check that it doesn't have any effect and user still has role
            deleteRoleMappingsInLDAP(roleMapper, robLdap, "realmRole1");
            deleteRoleMappingsInLDAP(roleMapper, robLdap, "realmRole2");
            robRoles = rob.getRealmRoleMappings();
            Assert.assertTrue(robRoles.contains(realmRole1));
            Assert.assertTrue(robRoles.contains(realmRole2));

            // Delete role mappings through model and verifies that user doesn't have them anymore
            rob.deleteRoleMapping(realmRole1);
            rob.deleteRoleMapping(realmRole2);
            rob.deleteRoleMapping(realmRole3);
            robRoles = rob.getRealmRoleMappings();
            Assert.assertFalse(robRoles.contains(realmRole1));
            Assert.assertFalse(robRoles.contains(realmRole2));
            Assert.assertFalse(robRoles.contains(realmRole3));
        } finally {
            keycloakRule.stopSession(session, false);
        }
    }

    private void deleteRoleMappingsInLDAP(RoleLDAPStorageMapper roleMapper, LDAPObject ldapUser, String roleName) {
        LDAPObject ldapRole1 = roleMapper.loadLDAPRoleByName(roleName);
        roleMapper.deleteRoleMappingInLDAP(ldapUser, ldapRole1);
    }
}
