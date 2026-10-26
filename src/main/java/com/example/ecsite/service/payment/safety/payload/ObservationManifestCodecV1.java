package com.example.ecsite.service.payment.safety.payload;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;

/** Exact closed V1 envelope. No ignored fields, enum fallback or target sorting/deduplication.
 * Bounds both wire bytes and JSONB's added comma/colon spacing. V1 numbers are only small integral
 * metadata; BIGINTs and other scalars use typed leaves. No JSON text is ever used for hashing.
 */
public final class ObservationManifestCodecV1 {
    public static final int FORMAT_VERSION=1;
    public static final int MAX_BYTES=1024*1024;
    private final PayloadEncodingV1 engine=new PayloadEncodingV1(new PaymentSafetyPayloadCodecV1.Limits(MAX_BYTES,64,200_000,MAX_BYTES,10_000));
    public String encode(ObservationRunRequest.Manifest manifest) {
        return engine.safe(()->{
            if(manifest==null)throw PayloadEncodingV1.invalid();
            var root=engine.object();root.put("version",FORMAT_VERSION);root.put("format","payment-safety-run-manifest");
            root.set("manifest",engine.encode(manifest,ObservationRunRequest.Manifest.class));
            String json=engine.json(root);
            if(json.getBytes(StandardCharsets.UTF_8).length+jsonbSpacing(root)>MAX_BYTES)throw PayloadEncodingV1.invalid();
            return json;
        });
    }
    public ObservationRunRequest.Manifest decode(String json) {
        return engine.safe(()->{
            var root=engine.parse(json);engine.keys(root,"version","format","manifest");
            var version=root.get("version");
            if(!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue()!=FORMAT_VERSION
                || !root.get("format").isString() || !"payment-safety-run-manifest".equals(root.get("format").stringValue()))throw PayloadEncodingV1.invalid();
            var manifest=(ObservationRunRequest.Manifest)engine.decode(root.get("manifest"),ObservationRunRequest.Manifest.class);
            if(manifest==null)throw PayloadEncodingV1.invalid();
            encode(manifest); // Includes JSONB capacity after arbitrary legal whitespace/key ordering.
            return manifest;
        });
    }
    private long jsonbSpacing(JsonNode node) {
        long count=node.isObject()?Math.max(0,2L*node.size()-1):node.isArray()?Math.max(0,node.size()-1):0;
        if(node.isObject())for(var key:node.propertyNames())count+=jsonbSpacing(node.get(key));
        else if(node.isArray())for(var item:node)count+=jsonbSpacing(item);
        return count;
    }
}
