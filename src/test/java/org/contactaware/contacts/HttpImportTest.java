package org.contactaware.contacts;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.contacts.SatnogsImporterTest.*;
import static org.contactaware.contacts.SatnogsImporterTest.PERIOD;

class HttpImportTest {
    @TempDir Path temp; HttpServer server; URI endpoint;
    @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/observations/");server.start();}
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    void reply(int status,String body,String link){server.createContext("/api/observations/",ex->{if(link!=null)ex.getResponseHeaders().add("Link",link);byte[] b=body.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(status,b.length);ex.getResponseBody().write(b);ex.close();});}
    @Test void followsLinkPaginationAndWritesVerifiedOfflineReplay()throws Exception{
        var calls=new AtomicInteger();server.createContext("/api/observations/",ex->{int page=calls.incrementAndGet();assertThat(ex.getRequestURI().getQuery()).contains("norad_cat_id=25544");if(page==1)ex.getResponseHeaders().add("Link","<"+endpoint+"?norad_cat_id=25544&cursor=two>; rel=\"next\"");byte[] body=JsonFiles.JSON.writeValueAsBytes(List.of(observation(page,1,page*200,page*200+100)));ex.sendResponseHeaders(200,body.length);ex.getResponseBody().write(body);ex.close();});
        Path out=temp.resolve("import");var result=new SatnogsImporter(new PublicHttp(),endpoint).fetch(25544,PERIOD,Set.of(),Set.of("good"),10,out);
        assertThat(calls.get()).isEqualTo(2);assertThat(result.contacts()).hasSize(2);assertThat(result.sources()).hasSize(2);Snapshots.verify(result,out);
        Main.run(new String[]{"satnogs-replay","--dataset",out.resolve("dataset.json").toString(),"--out",temp.resolve("replay").toString()});
        assertThat(Files.readAllBytes(out.resolve("contacts.json"))).isEqualTo(Files.readAllBytes(temp.resolve("replay/contacts.json")));
    }
    @Test void refusesPartialPaginationResults()throws Exception{
        reply(200,"["+observation(1,1,0,100)+"]","<"+endpoint+"?cursor=two>; rel=\"next\"");
        assertThatThrownBy(()->new SatnogsImporter(new PublicHttp(),endpoint).fetch(25544,PERIOD,Set.of(),Set.of("good"),1,temp)).hasMessageContaining("maxPages");assertThat(temp.resolve("dataset.json")).doesNotExist();
    }
    @Test void stopsOnHttpErrorWithoutPublishingDataset()throws Exception{
        reply(429,"rate limited",null);assertThatThrownBy(()->new SatnogsImporter(new PublicHttp(),endpoint).fetch(25544,PERIOD,Set.of(),Set.of("good"),10,temp)).hasMessageContaining("HTTP 429");assertThat(temp.resolve("dataset.json")).doesNotExist();
    }
    @Test void rejectsCrossOriginPagination()throws Exception{
        reply(200,"["+observation(1,1,0,100)+"]","<https://example.com/steal>; rel=\"next\"");assertThatThrownBy(()->new SatnogsImporter(new PublicHttp(),endpoint).fetch(25544,PERIOD,Set.of(),Set.of("good"),10,temp)).hasMessageContaining("pagination target");
    }
    @Test void rejectsCycles()throws Exception{
        reply(200,"["+observation(1,1,0,100)+"]","<"+endpoint+"?cursor=loop>; rel=\"next\"");assertThatThrownBy(()->new SatnogsImporter(new PublicHttp(),endpoint).fetch(25544,PERIOD,Set.of(),Set.of("good"),10,temp)).hasMessageContaining("cycle");
    }
}
