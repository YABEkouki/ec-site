package com.example.ecsite.support.display;

import java.net.URI;
import java.util.*;

/** Immutable synthetic MVC response. No live cookies, provider credentials or file lookup. */
public record DisplayPage(String name,String path,Map<String,String> headers,byte[] body) {
    public static final Set<String> HEADERS=Set.of("content-type","content-security-policy","cache-control","referrer-policy","x-content-type-options","x-frame-options");
    public DisplayPage {
        if(name==null||!name.matches("[a-z0-9-]+")||!safePath(path))throw new IllegalArgumentException("Invalid display route");
        headers=Map.copyOf(headers);body=body.clone();
        for(var h:headers.entrySet())if(!HEADERS.contains(h.getKey().toLowerCase(Locale.ROOT))||h.getValue().contains("\r")||h.getValue().contains("\n"))throw new IllegalArgumentException("Unsafe display header");
    }
    public static boolean safePath(String value){
        if(value==null||!value.startsWith("/")||value.startsWith("//")||value.contains("%")||value.contains("\\")||value.contains("#"))return false;
        try{var u=URI.create(value);return !u.isAbsolute()&&u.getRawAuthority()==null&&Arrays.stream(u.getPath().split("/",-1)).noneMatch(v->v.equals("..")||v.equals("."));}
        catch(IllegalArgumentException invalid){return false;}
    }
    @Override public byte[] body(){return body.clone();}
}
