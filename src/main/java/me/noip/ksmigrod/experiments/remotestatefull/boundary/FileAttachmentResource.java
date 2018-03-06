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
package me.noip.ksmigrod.experiments.remotestatefull.boundary;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;
import javax.mail.internet.ContentDisposition;
import javax.naming.NamingException;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import me.noip.ksmigrod.experiments.remotestatefull.control.FileAttachmentUploadBeanRemote;
import me.noip.ksmigrod.experiments.remotestatefull.entity.FileAttachment;
import me.noip.ksmigrod.experiments.remotestatefull.utils.ApiUtils;

/**
 *
 * @author Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>
 */
@Path("files")
@RequestScoped
public class FileAttachmentResource {

    private static final Logger log = Logger.getLogger(FileAttachmentResource.class.getName());
    
    @EJB(mappedName = "jndi:ext://app/FileAttachmentUploadBeanRemote")
    FileAttachmentUploadBeanRemote uploadBean;

    @POST
    public FileAttachment uploadFileAttachment(
            InputStream dataStream,
            @HeaderParam("Content-Disposition") ContentDisposition contentDisposition) {
        String fileName = ApiUtils.getFileNameFromContentDisposition(contentDisposition);
        uploadBean.init(fileName);
        try {
            byte[] bufor = new byte[64 * 1024];
            int bytesRead = 0;
            int offset = 0;
            while ((bytesRead = dataStream.read(bufor, offset, 64 * 1024 - offset)) != -1) {
                if (offset + bytesRead == 64 * 1024) {
                    uploadBean.write(bufor, 0, offset + bytesRead);
                    offset = 0;
                } else {
                    offset += bytesRead;
                }
            }
            if (offset != 0) {
                uploadBean.write(bufor, 0, offset);
            }
            return uploadBean.close();
        } catch (IOException ex) {
            uploadBean.abort();
            throw new WebApplicationException(Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"))
                    .entity(ex.toString())
                    .build());
        } finally {
            uploadBean.remove();
        }
        
    }
}
