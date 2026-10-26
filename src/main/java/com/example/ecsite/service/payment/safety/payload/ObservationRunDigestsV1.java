package com.example.ecsite.service.payment.safety.payload;

/** Domain-separated audit content hashes. Request UUID/actor are bound to requestDigest;
 * manifestDigest is reusable across request identities and contains only the frozen manifest.
 * Neither digest proves approval, fencing, target completeness or financial execution safety.
 */
public final class ObservationRunDigestsV1 {
    private ObservationRunDigestsV1() {}
    public static String requestDigest(ObservationRunRequest request) {
        if(request==null)throw PayloadEncodingV1.invalid();
        new ObservationManifestCodecV1().encode(request.manifest());
        return PayloadEncodingV1.digest("ec-site/payment-safety/observation-run-request",request);
    }
    public static String manifestDigest(ObservationRunRequest.Manifest manifest) {
        new ObservationManifestCodecV1().encode(manifest);
        return PayloadEncodingV1.digest("ec-site/payment-safety/observation-run-manifest",manifest);
    }
    public static void verifyRequest(ObservationRunRequest request,String expected){PayloadEncodingV1.verifyDigest(requestDigest(request),expected);}
    public static void verifyManifest(ObservationRunRequest.Manifest manifest,String expected){PayloadEncodingV1.verifyDigest(manifestDigest(manifest),expected);}
}
