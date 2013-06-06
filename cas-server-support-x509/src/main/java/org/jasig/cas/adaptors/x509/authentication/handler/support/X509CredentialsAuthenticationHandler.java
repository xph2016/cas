/*
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
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
package org.jasig.cas.adaptors.x509.authentication.handler.support;

import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.regex.Pattern;

import org.jasig.cas.adaptors.x509.authentication.principal.X509CertificateCredential;
import org.jasig.cas.adaptors.x509.util.CertUtils;
import org.jasig.cas.authentication.handler.AuthenticationException;
import org.jasig.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.jasig.cas.authentication.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;

/**
 * Authentication Handler that accepts X509 Certificates, determines their
 * validity and ensures that they were issued by a trusted issuer. (targeted at
 * X509v3) Optionally checks KeyUsage extension in the user certificate
 * (container should do that too). Note that this handler trusts the servlet
 * container to do some initial checks like path validation. Deployers can
 * supply an optional pattern to match subject dns against to further restrict
 * certificates in case they are not using their own issuer. It's also possible
 * to specify a maximum pathLength for the SUPPLIED certificates. (note that
 * this does not include a pathLength check for the root certificate)
 * [PathLength is 0 for the CA certificate that issues the end-user certificate]
 *
 * @author Scott Battaglia
 * @author Jan Van der Velpen
 * @since 3.0.4
 */
public class X509CredentialsAuthenticationHandler extends AbstractPreAndPostProcessingAuthenticationHandler {

    /** Default setting to limit the number of intermediate certificates. */
    private static final int DEFAULT_MAXPATHLENGTH = 1;

    /** Default setting whether to allow unspecified number of intermediate certificates. */
    private static final boolean DEFAULT_MAXPATHLENGTH_ALLOW_UNSPECIFIED = false;

    /** Default setting to check keyUsage extension. */
    private static final boolean DEFAULT_CHECK_KEYUSAGE = false;

    /** Default setting to force require "KeyUsage" extension. */
    private static final boolean DEFAULT_REQUIRE_KEYUSAGE = false;

    /** Default subject pattern match. */
    private static final Pattern DEFAULT_SUBJECT_DN_PATTERN = Pattern.compile(".*");

    /** OID for KeyUsage X.509v3 extension field. */
    private static final String KEY_USAGE_OID = "2.5.29.15";

    /** Instance of Logging. */
    private final Logger log = LoggerFactory.getLogger(getClass());

    /** The compiled pattern supplied by the deployer. */
    @NotNull
    private Pattern regExTrustedIssuerDnPattern;

    /**
     * Deployer supplied setting for maximum pathLength in a SUPPLIED
     * certificate.
     */
    private int maxPathLength = DEFAULT_MAXPATHLENGTH;

    /**
     * Deployer supplied setting to allow unlimited pathLength in a SUPPLIED
     * certificate.
     */
    private boolean maxPathLengthAllowUnspecified = DEFAULT_MAXPATHLENGTH_ALLOW_UNSPECIFIED;

    /** Deployer supplied setting to check the KeyUsage extension. */
    private boolean checkKeyUsage = DEFAULT_CHECK_KEYUSAGE;

    /**
     * Deployer supplied setting to force require the correct KeyUsage
     * extension.
     */
    private boolean requireKeyUsage = DEFAULT_REQUIRE_KEYUSAGE;

    /** The compiled pattern for trusted DN's supplied by the deployer. */
    @NotNull
    private Pattern regExSubjectDnPattern = DEFAULT_SUBJECT_DN_PATTERN;

    /** Certificate revocation checker component. */
    @NotNull
    private RevocationChecker revocationChecker = new NoOpRevocationChecker();


    @Override
    public boolean supports(final Credential credential) {
        return credential != null
                && X509CertificateCredential.class.isAssignableFrom(credential
                        .getClass());
    }

    @Override
    protected final boolean doAuthentication(final Credential credential)
            throws AuthenticationException {

        final X509CertificateCredential x509Credentials = (X509CertificateCredential) credential;
        final X509Certificate[] certificates = x509Credentials.getCertificates();

        X509Certificate clientCert = null;
        boolean valid = true;
        boolean hasTrustedIssuer = false;
        for (int i = certificates.length - 1; i >= 0; i--) {
            final X509Certificate certificate = certificates[i];
            try {
                log.debug("Evaluating {}", CertUtils.toString(certificate));

                validate(certificate);

                if (!hasTrustedIssuer) {
                    hasTrustedIssuer = isCertificateFromTrustedIssuer(certificate);
                }

                // getBasicConstraints returns pathLenContraint which is
                // >=0 when this is a CA cert and -1 when it's not
                int pathLength = certificate.getBasicConstraints();
                if (pathLength < 0) {
                    log.debug("Found valid client certificate");
                    clientCert = certificate;
                } else {
                    log.debug("Found valid CA certificate");
                }
            } catch (final GeneralSecurityException e) {
                log.warn("Failed to validate {}", CertUtils.toString(certificate), e);
                valid = false;
            }
        }
        if (valid && hasTrustedIssuer && clientCert != null) {
            x509Credentials.setCertificate(clientCert);
            log.info("Successfully authenticated {}", credential);
            return true;
        }
        log.info("Failed to authenticate {}", credential);
        return false;
    }

    public void setTrustedIssuerDnPattern(final String trustedIssuerDnPattern) {
        this.regExTrustedIssuerDnPattern = Pattern.compile(trustedIssuerDnPattern);
    }

    /**
     * @param maxPathLength The maxPathLength to set.
     */
    public void setMaxPathLength(final int maxPathLength) {
        this.maxPathLength = maxPathLength;
    }

    /**
     * @param allowed Allow CA certs to have unlimited intermediate certs (default=false).
     */
    public void setMaxPathLengthAllowUnspecified(final boolean allowed) {
        this.maxPathLengthAllowUnspecified = allowed;
    }

    /**
     * @param checkKeyUsage The checkKeyUsage to set.
     */
    public void setCheckKeyUsage(final boolean checkKeyUsage) {
        this.checkKeyUsage = checkKeyUsage;
    }

    /**
     * @param requireKeyUsage The requireKeyUsage to set.
     */
    public void setRequireKeyUsage(final boolean requireKeyUsage) {
        this.requireKeyUsage = requireKeyUsage;
    }

    public void setSubjectDnPattern(final String subjectDnPattern) {
        this.regExSubjectDnPattern = Pattern.compile(subjectDnPattern);
    }

    /**
     * Sets the component responsible for evaluating certificate revocation status for client
     * certificates presented to handler. The default checker is a NO-OP implementation
     * for backward compatibility with previous versions that do not perform revocation
     * checking.
     *
     * @param checker Revocation checker component.
     */
    public void setRevocationChecker(final RevocationChecker checker) {
        this.revocationChecker = checker;
    }

    private void validate(final X509Certificate cert) throws GeneralSecurityException {
        cert.checkValidity();
        this.revocationChecker.check(cert);

        int pathLength = cert.getBasicConstraints();
        if (pathLength < 0) {
            if (!isCertificateAllowed(cert)) {
                throw new GeneralSecurityException(
                        "Certificate subject does not match pattern " + this.regExSubjectDnPattern.pattern());
            }
            if (this.checkKeyUsage && !isValidKeyUsage(cert)) {
                throw new GeneralSecurityException(
                        "Certificate keyUsage constraint forbids SSL client authentication.");
            }
        } else {
            // Check pathLength for CA cert
            if (pathLength == Integer.MAX_VALUE && this.maxPathLengthAllowUnspecified != true) {
                throw new GeneralSecurityException("Unlimited certificate path length not allowed by configuration.");
            } else if (pathLength > this.maxPathLength && pathLength < Integer.MAX_VALUE) {
                throw new GeneralSecurityException(String.format(
                        "Certificate path length %s exceeds maximum value %s.", pathLength, this.maxPathLength));
            }
        }
    }

    private boolean isValidKeyUsage(final X509Certificate certificate) {
        log.debug("Checking certificate keyUsage extension");

        /*
         * KeyUsage ::= BIT STRING { digitalSignature (0), nonRepudiation (1),
         * keyEncipherment (2), dataEncipherment (3), keyAgreement (4),
         * keyCertSign (5), cRLSign (6), encipherOnly (7), decipherOnly (8) }
         */
        final boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage == null) {
            log.warn("Configuration specifies checkKeyUsage but keyUsage extension not found in certificate.");
            return !this.requireKeyUsage;
        }

        final boolean valid;
        if (isCritical(certificate, KEY_USAGE_OID) || this.requireKeyUsage) {
            log.debug("KeyUsage extension is marked critical or required by configuration.");
            valid = keyUsage[0];
        } else {
            log.debug(
               "KeyUsage digitalSignature=%s, Returning true since keyUsage validation not required by configuration.");
            valid = true;
        }
        return valid;
    }

    private boolean isCritical(final X509Certificate certificate, final String extensionOid) {
        final Set<String> criticalOids = certificate.getCriticalExtensionOIDs();

        if (criticalOids == null || criticalOids.isEmpty()) {
            return false;
        }

        return criticalOids.contains(extensionOid);
    }

    private boolean isCertificateAllowed(final X509Certificate cert) {
        return doesNameMatchPattern(cert.getSubjectDN(), this.regExSubjectDnPattern);
    }

    private boolean isCertificateFromTrustedIssuer(final X509Certificate cert) {
        return doesNameMatchPattern(cert.getIssuerDN(), this.regExTrustedIssuerDnPattern);
    }

    private boolean doesNameMatchPattern(final Principal principal,
            final Pattern pattern) {
        final String name = principal.getName();
        final boolean result = pattern.matcher(name).matches();
        log.debug(String.format("%s matches %s == %s", pattern.pattern(), name, result));
        return result;
    }
}
