package com.influora.security;

import com.influora.domain.enums.UserType;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AuthPrincipal implements UserDetails {

    private final String userId;
    private final String email;
    private final UserType userType;
    private final String workspaceId;

    public AuthPrincipal(String userId, String email, UserType userType, String workspaceId) {
        this.userId = userId;
        this.email = email;
        this.userType = userType;
        this.workspaceId = workspaceId;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public UserType getUserType() {
        return userType;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + userType.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return userId;
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
