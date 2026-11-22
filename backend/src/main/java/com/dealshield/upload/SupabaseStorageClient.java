package com.dealshield.upload;

import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Uses the verified user's JWT, so Storage RLS remains authoritative. */
public final class SupabaseStorageClient {
    public static final String BUCKET="dealshield-quotations";
    private final RestClient client;
    public SupabaseStorageClient(String projectUrl,String publishableKey,RestClient.Builder builder){
        URI uri=URI.create(projectUrl);if(!"https".equals(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException("Invalid project URL");
        this.client=builder.baseUrl(projectUrl.replaceAll("/+$","")+"/storage/v1").defaultHeader("apikey",publishableKey).build();
    }
    public void upload(String userToken,String path,String mime,byte[] bytes){
        try{client.post().uri("/object/"+BUCKET+"/"+path).headers(h->h.setBearerAuth(userToken)).contentType(MediaType.parseMediaType(mime)).body(bytes).retrieve().toBodilessEntity();}
        catch(RestClientException e){throw new StorageFailure("Private storage upload failed",e);}
    }
    public static final class StorageFailure extends RuntimeException{StorageFailure(String m,Throwable c){super(m,c);}}
}
