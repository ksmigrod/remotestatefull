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

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Remove;
import javax.ejb.Stateful;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceUnit;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.xml.bind.DatatypeConverter;
import me.noip.ksmigrod.experiments.remotestatefull.entity.FileAttachment;

/**
 *
 * @author Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>
 */
@Stateful(passivationCapable = false)
@TransactionManagement(TransactionManagementType.BEAN)
public class FileAttachmentUploadBean implements FileAttachmentUploadBeanRemote {

    private static final Logger logger
            = Logger.getLogger(FileAttachmentUploadBean.class.getName());

    @PersistenceUnit
    EntityManagerFactory emf;

    @Resource
    UserTransaction utx;

    MessageDigest md;

    private EntityManager em;
    private long fileAttachmentId;
    private Blob blob;
    private OutputStream os;

    @Override
    public void init(final String fileName) {
        try {
            this.md = MessageDigest.getInstance("SHA-256");
            utx.begin();
        } catch (NoSuchAlgorithmException | NotSupportedException | SystemException ex) {
            logger.log(Level.SEVERE, "Opening transaction.", ex);
            throw new IllegalStateException(ex);
        }
        this.em = emf.createEntityManager();
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
            Connection conn = em.unwrap(Connection.class);
            this.blob = conn.createBlob();
            this.os = blob.setBinaryStream(1);
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, null, ex);
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public FileAttachment close() {
        Connection conn = em.unwrap(Connection.class);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE FILE_ATTACHMENTS"
                + " SET FILE_DATA = ?"
                + " WHERE FILE_ID = ?")) {
            ps.setBlob(1, blob);
            ps.setLong(2, fileAttachmentId);
            int executeUpdate = ps.executeUpdate();
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, null, ex);
            throw new IllegalStateException(ex);
        }
        FileAttachment fa = em.find(FileAttachment.class, fileAttachmentId);
        em.refresh(fa);
        fa.setCheckSum(DatatypeConverter.printHexBinary(md.digest()).toUpperCase());
        em.close();
        try {
            utx.commit();
        } catch (RollbackException | HeuristicMixedException | HeuristicRollbackException
                | SecurityException | IllegalStateException | SystemException ex) {
            logger.log(Level.SEVERE, null, ex);
        }
        return fa;
    }

    @Override
    public void abort() {
        em.close();
        try {
            utx.rollback();
        } catch (IllegalStateException | SecurityException | SystemException ex) {
            logger.log(Level.SEVERE, null, ex);
        }
    }

    @Override
    @Remove
    public void remove() {
    }

    @Override
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
