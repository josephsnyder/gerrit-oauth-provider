// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.oauth;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.OutputFormat;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.apache.commons.codec.binary.Base64;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletResponse;

@Singleton
class GoogleOAuthService implements OAuthServiceProvider {
  private static final Logger log =
      LoggerFactory.getLogger(GoogleOAuthService.class);
  static final String CONFIG_SUFFIX = "-google-oauth";
  private static final String PROTECTED_RESOURCE_URL =
      "https://www.googleapis.com/userinfo/v2/me";
      //"https://www.googleapis.com/plus/v1/people/me/openIdConnect";
  private static final String SCOPE = "email profile";
  private final OAuthService service;
  private final String canonicalWebUrl;
  private final boolean linkToExistingOpenIDAccounts;

  @Inject
  GoogleOAuthService(PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(
        pluginName + CONFIG_SUFFIX);
    this.canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(
        urlProvider.get()) + "/";
    this.linkToExistingOpenIDAccounts = cfg.getBoolean(
        "link-to-existing-openid-accounts", false);
    String scope = linkToExistingOpenIDAccounts
        ? "openid " + SCOPE
        : SCOPE;
    this.service = new ServiceBuilder()
        .provider(Google2Api.class)
        .apiKey(cfg.getString("client-id"))
        .apiSecret(cfg.getString("client-secret"))
        .callback(canonicalWebUrl + "oauth")
        .scope(scope)
        .build();
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: canonicalWebUrl={}", canonicalWebUrl);
      log.debug("OAuth2: scope={}", scope);
      log.debug("OAuth2: linkToExistingOpenIDAccounts={}",
          linkToExistingOpenIDAccounts);
    }
  }

  @Override
  public OAuthToken getRequestToken() {
    throw new IllegalStateException("Not supported workflow in OAuth 2.0");
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    OAuthRequest request = new OAuthRequest(Verb.GET, PROTECTED_RESOURCE_URL);
    Token t =
        new Token(token.getToken(), token.getSecret(), token.getRaw());
    service.signRequest(t, request);
    Response response = request.send();
    if (response.getCode() != HttpServletResponse.SC_OK) {
      throw new IOException(String.format("Status %s (%s) for request %s",
          response.getCode(), response.getBody(), request.getUrl()));
    }
    JsonElement userJson =
        OutputFormat.JSON.newGson().fromJson(response.getBody(),
            JsonElement.class);
    if (userJson.isJsonObject()) {
      JsonObject jsonObject = userJson.getAsJsonObject();
      JsonElement id = jsonObject.get("id");
      if (id.isJsonNull()) {
        throw new IOException(String.format(
            "Response doesn't contain id field"));
      }
      JsonElement email = jsonObject.get("email");
      JsonElement name = jsonObject.get("name");
      String claimedIdentifier = null;

      if (linkToExistingOpenIDAccounts) {
        claimedIdentifier = lookupClaimedIdentity(token);
      }
      return new OAuthUserInfo(id.getAsString() /*externalId*/,
          null /*username*/,
          email.isJsonNull() ? null : email.getAsString() /*email*/,
          name.isJsonNull() ? null : name.getAsString() /*displayName*/,
	      claimedIdentifier /*claimedIdentity*/);
    } else {
        throw new IOException(String.format(
            "Invalid JSON '%s': not a JSON Object", userJson));
    }
  }

  /**
   * @param token
   * @return OpenID id token, when contained in id_token, null otherwise
   */
  private static String lookupClaimedIdentity(OAuthToken token) {
    JsonElement idToken =
      OutputFormat.JSON.newGson().fromJson(token.getRaw(), JsonElement.class);
    if (idToken.isJsonObject()) {
      JsonObject idTokenObj = idToken.getAsJsonObject();
      JsonElement idTokenElement = idTokenObj.get("id_token");
      if (!idTokenElement.isJsonNull()) {
        String payload = decodePayload(idTokenElement.getAsString());
        if (!Strings.isNullOrEmpty(payload)) {
          JsonElement openidIdToken =
            OutputFormat.JSON.newGson().fromJson(payload, JsonElement.class);
          if (openidIdToken.isJsonObject()) {
            JsonObject openidIdObj = openidIdToken.getAsJsonObject();
            JsonElement openidIdElement = openidIdObj.get("openid_id");
            if (!openidIdElement.isJsonNull()) {
              String openIdId = openidIdElement.getAsString();
              log.debug("OAuth2: openid_id={}", openIdId);
              return openIdId;
            }
            log.debug("OAuth2: JWT doesn't contain openid_id element");
          }
        }
      }
    }
    return null;
  }

  /**
   * Decode payload from JWT according to spec:
   * "header.payload.signature"
   *
   * @param idToken Base64 encoded tripple, separated with dot
   * @return openid_id part of payload, when contained, null otherwise
   */
  private static String decodePayload(String idToken) {
    Preconditions.checkNotNull(idToken);
    String[] jwtParts = idToken.split("\\.");
    Preconditions.checkState(jwtParts.length == 3);
    String payloadStr = jwtParts[1];
    Preconditions.checkNotNull(payloadStr);
    return new String(Base64.decodeBase64(payloadStr));
  }

  @Override
  public OAuthToken getAccessToken(OAuthToken rt,
      OAuthVerifier rv) {
    Token ti = null;
    if (rt != null) {
      ti = new Token(rt.getToken(), rt.getSecret(), rt.getRaw());
    }
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(ti, vi);
    OAuthToken result = new OAuthToken(to.getToken(),
        to.getSecret(), to.getRawResponse());
     return result;
  }

  @Override
  public String getAuthorizationUrl(OAuthToken rt) {
    Token ti = null;
    if (rt != null) {
      ti = new Token(rt.getToken(), rt.getSecret(), rt.getRaw());
    }

    String url = service.getAuthorizationUrl(ti);
    try {
      if (linkToExistingOpenIDAccounts) {
        url += "&openid.realm=" + URLEncoder.encode(canonicalWebUrl,
            StandardCharsets.UTF_8.name());
      }
    } catch (UnsupportedEncodingException e) {
      throw new IllegalArgumentException(e);
    }
    if (log.isDebugEnabled()) {
      log.debug("OAuth2: authorization URL={}", url);
    }
    return url;
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return "Google OAuth2";
  }
}
