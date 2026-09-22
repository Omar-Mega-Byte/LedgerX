package com.ledgerx.access;

import com.ledgerx.api.MalformedRequestException;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Adapts transport identity to the ownership context used by financial application services.
 *
 * <p>A production request has an authenticated JWT, so its signed {@value #OWNER_ID_CLAIM} claim is
 * authoritative and any development header is ignored. Local and test profiles have no JWT resource
 * server and retain the explicit, forgeable header seam for repeatable API exercises.
 */
@Component
public class OwnerContextResolver {

  public static final String OWNER_ID_CLAIM = "ledgerx_owner_id";

  public OwnerContext resolve(String developmentOwnerId, Jwt authenticatedToken) {
    if (authenticatedToken != null) {
      return fromJwtClaim(authenticatedToken.getClaimAsString(OWNER_ID_CLAIM));
    }
    return fromDevelopmentHeader(developmentOwnerId);
  }

  private OwnerContext fromJwtClaim(String ownerId) {
    if (!StringUtils.hasText(ownerId)) {
      throw new OwnerIdentityException(
          "authenticated token does not contain a LedgerX owner identifier");
    }
    try {
      return new OwnerContext(UUID.fromString(ownerId));
    } catch (IllegalArgumentException exception) {
      throw new OwnerIdentityException("authenticated LedgerX owner identifier is invalid");
    }
  }

  private OwnerContext fromDevelopmentHeader(String ownerId) {
    if (!StringUtils.hasText(ownerId)) {
      throw new MalformedRequestException(
          "owner header is required", new IllegalArgumentException("owner header is blank"));
    }
    try {
      return new OwnerContext(UUID.fromString(ownerId));
    } catch (IllegalArgumentException exception) {
      throw new MalformedRequestException("owner header must be a UUID", exception);
    }
  }
}
