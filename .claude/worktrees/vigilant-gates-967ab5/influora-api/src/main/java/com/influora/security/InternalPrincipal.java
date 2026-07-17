package com.influora.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authentication principal for a request that has passed BOTH internal-mesh checks:
 * {@link InternalServiceTokenFilter} (service token, {@code aud=influora-internal}) and
 * {@link InternalRequestVerifier} (HMAC signature + nonce). Distinct from {@link AuthPrincipal}
 * (human user JWT) — the on-behalf human JWT this call also carries is resolved separately by
 * {@link OnBehalfAuthResolver} and is NOT folded into this principal, so a service token alone
 * can never be mistaken for a fully-authorized human action.
 */
public class InternalPrincipal implements UserDetails {

    private final String serviceSubject;

    public InternalPrincipal(String serviceSubject) {
        this.serviceSubject = serviceSubject;
    }

    public String getServiceSubject() {
        return serviceSubject;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return serviceSubject;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
