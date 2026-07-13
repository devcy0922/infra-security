package dev.infrasec.spi;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.*;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LegacyUserAdapter — 레거시 DB 유저를 Keycloak UserModel로 래핑
 *
 * <p>AbstractUserAdapterFederatedStorage를 상속하여
 * 레거시 유저 속성을 Keycloak 세션에서 일관된 인터페이스로 제공한다.
 */
public class LegacyUserAdapter extends AbstractUserAdapterFederatedStorage {

    private final CustomUserStorageProvider.LegacyUser legacy;

    public LegacyUserAdapter(
            KeycloakSession session,
            RealmModel realm,
            ComponentModel model,
            CustomUserStorageProvider.LegacyUser legacy) {
        super(session, realm, model);
        this.legacy = legacy;
    }

    @Override
    public String getId() {
        return StorageId.keycloakId(storageProviderModel, String.valueOf(legacy.id()));
    }

    @Override
    public String getUsername() {
        return legacy.username();
    }

    @Override
    public void setUsername(String username) {
        // 읽기 전용 (마이그레이션 전까지 외부 DB에서만 관리)
    }

    @Override
    public String getEmail() {
        return legacy.email();
    }

    @Override
    public boolean isEnabled() {
        return legacy.isActive();
    }

    @Override
    public boolean isEmailVerified() {
        return true;
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        return Map.of(
                "department",  List.of(legacy.department() != null ? legacy.department() : ""),
                "legacy_role", List.of(legacy.role() != null ? legacy.role() : ""),
                "full_name",   List.of(legacy.fullName() != null ? legacy.fullName() : "")
        );
    }

    @Override
    public List<String> getAttribute(String name) {
        return switch (name) {
            case "department"  -> List.of(legacy.department() != null ? legacy.department() : "");
            case "legacy_role" -> List.of(legacy.role() != null ? legacy.role() : "");
            case "full_name"   -> List.of(legacy.fullName() != null ? legacy.fullName() : "");
            default -> List.of();
        };
    }

    @Override
    public String getFirstAttribute(String name) {
        List<String> attrs = getAttribute(name);
        return attrs.isEmpty() ? null : attrs.get(0);
    }

    @Override
    public Set<GroupModel> getGroups() {
        return Set.of();
    }

    @Override
    public Set<RoleModel> getRealmRoleMappings() {
        return Set.of();
    }
}
