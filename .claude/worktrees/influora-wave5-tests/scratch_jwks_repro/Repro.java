import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.JwkSet;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Repro {
    public static void main(String[] args) throws Exception {
        String pem = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELrXXRt8JBjhGurqemiISR/J9ly66\nU4j8cu6sI5p3Oo1/nvzVppb2NcJp6mzHpyrLB7ieYvQbhBOBkCx+AANRVQ==\n-----END PUBLIC KEY-----";
        StringBuilder base64 = new StringBuilder();
        for (String line : pem.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("-----")) continue;
            base64.append(line);
        }
        byte[] der = Base64.getDecoder().decode(base64.toString());
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey key = kf.generatePublic(new X509EncodedKeySpec(der));
        ECPublicKey ecKey = (ECPublicKey) key;

        JwkSet jwkSet = Jwks.set().add(
            Jwks.builder().key(ecKey).id("spring-dev-es256-1").operations().add(Jwks.OP.VERIFY).and().build()
        ).build();

        System.out.println("Built JwkSet OK: " + jwkSet.getClass());

        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(jwkSet);
        System.out.println("Jackson serialized: " + json);
    }
}
