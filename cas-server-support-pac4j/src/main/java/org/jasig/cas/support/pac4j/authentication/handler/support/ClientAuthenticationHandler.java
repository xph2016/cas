/*
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.support.pac4j.authentication.handler.support;

import org.jasig.cas.authentication.Credential;
import org.jasig.cas.authentication.HandlerResult;
import org.jasig.cas.authentication.PreventedException;
import org.jasig.cas.support.pac4j.authentication.principal.ClientCredential;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.profile.UserProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.webflow.context.ExternalContextHolder;
import org.springframework.webflow.context.servlet.ServletExternalContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import java.security.GeneralSecurityException;

/**
 * Pac4j authentication handler which gets the credentials and then the user profile
 * in a delegated authentication process from an external identity provider.
 *
 * @author Jerome Leleu
 * @since 3.5.0
 */
@Component("clientAuthenticationHandler")
@SuppressWarnings("unchecked")
public class ClientAuthenticationHandler extends AbstractPac4jAuthenticationHandler {

    /**
     * The clients for authentication.
     */
    @NotNull
    @Autowired
    private Clients clients;

    @Override
    public final boolean supports(final Credential credential) {
        return credential != null && ClientCredential.class.isAssignableFrom(credential.getClass());
    }

    @Override
    protected HandlerResult doAuthentication(final Credential credential) throws GeneralSecurityException, PreventedException {
        final ClientCredential clientCredentials = (ClientCredential) credential;
        logger.debug("clientCredentials  {}", clientCredentials);

        final Credentials credentials = clientCredentials.getCredentials();
        final String clientName = credentials.getClientName();
        logger.debug("clientName:  {}", clientName);

        // get client
        final Client<Credentials, UserProfile> client = this.clients.findClient(clientName);
        logger.debug("client: {}", client);

        // web context
        final ServletExternalContext servletExternalContext = (ServletExternalContext) ExternalContextHolder.getExternalContext();
        final HttpServletRequest request = (HttpServletRequest) servletExternalContext.getNativeRequest();
        final HttpServletResponse response = (HttpServletResponse) servletExternalContext.getNativeResponse();
        final WebContext webContext = new J2EContext(request, response);

        // get user profile
        final UserProfile userProfile = client.getUserProfile(credentials, webContext);
        logger.debug("userProfile: {}", userProfile);

        return createResult(clientCredentials, userProfile);
    }

    public Clients getClients() {
        return clients;
    }

    public void setClients(final Clients clients) {
        this.clients = clients;
    }
}
