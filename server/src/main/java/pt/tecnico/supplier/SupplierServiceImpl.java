package pt.tecnico.supplier;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

import com.google.protobuf.ByteString;
import com.google.type.Money;

import io.grpc.stub.StreamObserver;
import pt.tecnico.supplier.domain.Supplier;
import pt.tecnico.supplier.grpc.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.security.MessageDigest;

public class SupplierServiceImpl extends SupplierGrpc.SupplierImplBase {

	/**
	 * Set flag to true to print debug messages. The flag can be set using the
	 * -Ddebug command line option.
	 */
	private static final boolean DEBUG_FLAG = (System.getProperty("debug") != null);

	/** Helper method to print debug messages. */
	private static void debug(String debugMessage) {
		if (DEBUG_FLAG)
			System.err.println(debugMessage);
	}

	/** Domain object. */
	final private Supplier supplier = Supplier.getInstance();

	/** Constructor */
	public SupplierServiceImpl() {
		debug("Loading demo data...");
		supplier.demoData();
	}

	/** Helper method to convert domain product to message product. */
	private Product buildProductFromProduct(pt.tecnico.supplier.domain.Product p) {
		Product.Builder productBuilder = Product.newBuilder();
		productBuilder.setIdentifier(p.getId());
		productBuilder.setDescription(p.getDescription());
		productBuilder.setQuantity(p.getQuantity());

		Money.Builder moneyBuilder = Money.newBuilder();
		moneyBuilder.setCurrencyCode("EUR").setUnits(p.getPrice());
		productBuilder.setPrice(moneyBuilder.build());

		return productBuilder.build();
	}

	@Override
	public void listProducts(ProductsRequest request, StreamObserver<SignedResponse> responseObserver) {
		debug("listProducts called");

		debug("Received request:");
		debug(request.toString());
		debug("in binary hexadecimals:");
		byte[] requestBinary = request.toByteArray();
		debug(String.format("%d bytes%n", requestBinary.length));

		// build response
		ProductsResponse.Builder responseBuilder = ProductsResponse.newBuilder();
		responseBuilder.setSupplierIdentifier(supplier.getId());
		for (String pid : supplier.getProductsIDs()) {
			pt.tecnico.supplier.domain.Product p = supplier.getProduct(pid);
			Product product = buildProductFromProduct(p);
			responseBuilder.addProduct(product);
		}
		ProductsResponse response = responseBuilder.build();

		debug("Response to send:");
		debug(response.toString());
		debug("in binary hexadecimals:");
		byte[] responseBinary = response.toByteArray();
		debug(printHexBinary(responseBinary));
		debug(String.format("%d bytes%n", responseBinary.length));

		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			messageDigest.update(responseBinary);
			byte[] digest = messageDigest.digest();
			debug("Digest: " + printHexBinary(digest));

			Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, readKey("secret.key"));
			byte[] cipherDigest = cipher.doFinal(digest);
			ByteString cipherDigestByteString = ByteString.copyFrom(cipherDigest);

			Signature signature = Signature.newBuilder()
					.setSignerId(supplier.getId())
					.setValue(cipherDigestByteString)
					.build();

			//!! TEST SIGNATURE EFFICACY !!//

			// to test: change below to true, then see if client rejects message
			if (false) {
				response = response.toBuilder()
						.addProduct(Product.newBuilder()
								.setIdentifier("THIS SHOULD FAIL")
								.build())
						.build();
			}

			//!! END TEST SIGNATURE EFFICACY !!//

			SignedResponse signedResponse = SignedResponse.newBuilder()
					.setResponse(response)
					.setSignature(signature)
					.build();

			// send single response back
			responseObserver.onNext(signedResponse);
			// complete call
			responseObserver.onCompleted();
		} catch (Exception e) {
			e.printStackTrace();
			responseObserver.onError(e);
		}
	}

	public static SecretKeySpec readKey(String resourcePathName) throws Exception {
		debug("Reading key from resource " + resourcePathName + " ...");

		try (InputStream fis = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePathName)) {
			assert fis != null;
			byte[] encoded = new byte[fis.available()];
			debug("Read " + fis.read(encoded) + " bytes");

			debug("Key: " + printHexBinary(encoded));

			return new SecretKeySpec(encoded, "AES");
		}
	}

}
