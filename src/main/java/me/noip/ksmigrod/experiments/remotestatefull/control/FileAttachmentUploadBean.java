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
package me.noip.ksmigrod.experiments.remotestatefull.control;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Remove;
import javax.ejb.Stateful;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.xml.bind.DatatypeConverter;
import me.noip.ksmigrod.experiments.remotestatefull.entity.FileAttachment;

/**
 *
 * @author Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>
 */
@Stateful(passivationCapable = false)
public class FileAttachmentUploadBean implements FileAttachmentUploadBeanRemote {

    private static final Logger logger
            = Logger.getLogger(FileAttachmentUploadBean.class.getName());

    @PersistenceContext
    EntityManager em;

    MessageDigest md;

    private long fileAttachmentId;
    private File tempFile;
    private OutputStream os;

    @Override
    public void init(final String fileName) {
        try {
            this.md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Intitializing file upload SFSB", ex);
        }
        try {
            FileAttachment fileAttachment = new FileAttachment();
            fileAttachment.setFileName(fileName);
            em.persist(fileAttachment);
            em.flush();
            this.fileAttachmentId = fileAttachment.getId();
        } catch (Exception ex) {
            Throwable t = ex;
            do {
                if (t instanceof ConstraintViolationException) {
                    ConstraintViolationException cve = (ConstraintViolationException) t;
                    for (ConstraintViolation<?> cv : cve.getConstraintViolations()) {
                        logger.log(Level.WARNING, "ConstraintViolation {0}", cv);
                    }
                }
            } while ((t = t.getCause()) != null);
            throw ex;
        }
        try {
            tempFile = File.createTempFile("upload-" + fileAttachmentId + "-", "tmp");
            os = new FileOutputStream(tempFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Creating temporary file in upload SFSB", ex);
        }
    }

    @Override
    public FileAttachment close() {
        try {
            os.close();
            os = null;
        } catch (IOException ex) {
            throw new IllegalStateException("Closing temporary file in file upload SFSB", ex);
        }
        Connection conn = em.unwrap(Connection.class);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE FILE_ATTACHMENTS"
                + " SET FILE_DATA = ?"
                + " WHERE FILE_ID = ?");
                InputStream is = new BufferedInputStream(new FileInputStream(tempFile))) {
            ps.setBinaryStream(1, is);
            ps.setLong(2, fileAttachmentId);
            ps.executeUpdate();
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("Writing temporary file content to database in SFSB.", ex);
        }
        tempFile.delete();
        tempFile = null;
        FileAttachment fa = em.find(FileAttachment.class, fileAttachmentId);
        em.refresh(fa);
        fa.setCheckSum(DatatypeConverter.printHexBinary(md.digest()).toUpperCase());
        em.flush();
        em.detach(fa);
        return fa;
    }

    @Override
    public void abort() {
        em.remove(em.find(FileAttachment.class, fileAttachmentId));

    }

    @Override
    @Remove
    public void remove() {
        if (os != null) {
            try {
                os.close();
            } catch (IOException ex) {
            }
        }
        if (tempFile != null) {
            tempFile.delete();
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void write(final byte[] buffer, final int offset, final int length) {
        if (os == null) {
            throw new IllegalStateException("Output stream is not open");
        }
        try {
            os.write(buffer, offset, length);
            md.update(buffer, offset, length);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
