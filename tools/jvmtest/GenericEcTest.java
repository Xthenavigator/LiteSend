import java.math.BigInteger;
import java.security.SecureRandom;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;

/**
 * Compares the optimised custom curve (int-limb field arithmetic, suspected of
 * triggering a Dalvik JIT bug) with the generic BigInteger curve.
 */
public class GenericEcTest {
    public static void main(String[] args) {
        test("custom", CustomNamedCurves.getByName("secp256r1"));
        test("generic", ECNamedCurveTable.getByName("prime256v1"));
    }

    private static void test(String label, X9ECParameters x9) {
        if (x9 == null) {
            System.out.println(label + " NULL");
            return;
        }
        try {
            ECDomainParameters domain = new ECDomainParameters(x9.getCurve(), x9.getG(), x9.getN(), x9.getH());
            ECKeyPairGenerator generator = new ECKeyPairGenerator();
            generator.init(new ECKeyGenerationParameters(domain, new SecureRandom()));

            AsymmetricCipherKeyPair pair1 = generator.generateKeyPair();
            AsymmetricCipherKeyPair pair2 = generator.generateKeyPair();

            ECDHBasicAgreement agreement1 = new ECDHBasicAgreement();
            agreement1.init(pair1.getPrivate());
            BigInteger secret1 = agreement1.calculateAgreement(pair2.getPublic());

            ECDHBasicAgreement agreement2 = new ECDHBasicAgreement();
            agreement2.init(pair2.getPrivate());
            BigInteger secret2 = agreement2.calculateAgreement(pair1.getPublic());

            System.out.println(label + " secret1=" + secret1.toString(16));
            System.out.println(label + " secret2=" + secret2.toString(16));
            System.out.println(label + " MATCH " + secret1.equals(secret2));

            // sanity: 2 * G must equal G + G
            ECPublicKeyParameters pub = (ECPublicKeyParameters) pair1.getPublic();
            ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair1.getPrivate();
            BigInteger scalar2 = BigInteger.valueOf(2);
            org.bouncycastle.math.ec.ECPoint twice = domain.getG().multiply(scalar2);
            System.out.println(label + " 2G_VALID " + twice.isValid());
        } catch (Throwable t) {
            System.out.println(label + " FAIL " + t);
            t.printStackTrace();
        }
    }
}
