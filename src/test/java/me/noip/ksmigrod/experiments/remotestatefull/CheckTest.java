/*
 * The MIT License
 *
 * Copyright 2018 Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.noip.ksmigrod.experiments.remotestatefull;

import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.Status.Family;
import javax.xml.bind.DatatypeConverter;
import me.noip.ksmigrod.experiments.remotestatefull.control.FileAttachmentUploadBeanRemote;
import me.noip.ksmigrod.experiments.remotestatefull.entity.FileAttachment;
import static org.hamcrest.CoreMatchers.is;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.extension.rest.client.ArquillianResteasyResource;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ClassLoaderAsset;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import static org.junit.Assert.assertThat;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 * @author Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>
 */
@RunWith(Arquillian.class)
public class CheckTest {
    
    @Deployment(order = 1, name = "app")
    @TargetsContainer("tomee-app")
    public static WebArchive createDeploymentApp() {
        return ShrinkWrap.create(WebArchive.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.entity.FileAttachment.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.control.FileAttachmentUploadBeanRemote.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.control.FileAttachmentUploadBean.class)
                .addAsManifestResource(new ClassLoaderAsset("META-INF/persistence.xml"), "persistence.xml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                ;
                
    }
    
    @Deployment(order = 2, name = "web")
    @TargetsContainer("tomee-web")
    public static WebArchive createDeploymentWeb() {
        return ShrinkWrap.create(WebArchive.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.entity.FileAttachment.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.platform.ApplicationConfig.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.utils.ApiUtils.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.boundary.FileAttachmentResource.class)
                .addClass(me.noip.ksmigrod.experiments.remotestatefull.control.FileAttachmentUploadBeanRemote.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                ;
    }
    
    @Test
    @InSequence(1)
    @OperateOnDeployment("app")
    public void localUpload(@ArquillianResource URL url, @ArquillianResource InitialContext ctx) throws NamingException, NoSuchAlgorithmException {
        FileAttachmentUploadBeanRemote bean
                = (FileAttachmentUploadBeanRemote) ctx.lookup("java:global/"+url.getPath()+"/FileAttachmentUploadBean");
        byte []testData = new byte[96*1024];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte)(Byte.MIN_VALUE + i % 256);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(testData);
        bean.init("test123.local");
        bean.write(Arrays.copyOfRange(testData, 0, 64*1024), 0, 64*1024);
        bean.write(Arrays.copyOfRange(testData, 64*1024, 96*1024), 0, 32*1024);
        FileAttachment fa = bean.close();
        assertThat("Checksums do not match.", fa.getCheckSum(), is(DatatypeConverter.printHexBinary(digest).toUpperCase()));
    }
    
    @Test
    @InSequence(2)
    @RunAsClient
    @OperateOnDeployment("web")
    public void uploadViaRest(
            @ArquillianResteasyResource("rest/files") WebTarget webTarget) throws NoSuchAlgorithmException {
        byte []testData = new byte[96*1024];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte)(Byte.MIN_VALUE + i % 256);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(testData);
        Response response = webTarget
                .request()
                .header("Content-Disposition", "attachment; filename=\"test123.remote\"")
                .buildPost(Entity.entity(testData, MediaType.APPLICATION_OCTET_STREAM))
                .invoke();
        
        Status resultStatus = Status.fromStatusCode(response.getStatus());
        assertThat("Not successful.", resultStatus.getFamily(), is(Family.SUCCESSFUL));

        response.bufferEntity();
        FileAttachment readEntity = response.readEntity(FileAttachment.class);
        assertThat("Checksums do not match.", readEntity.getCheckSum(), is(DatatypeConverter.printHexBinary(digest).toUpperCase()));
    }
}
