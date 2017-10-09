/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2016 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.message.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.activation.DataSource;
import javax.mail.AuthenticationFailedException;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.FlagTerm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.message.db.EmailAddress;
import com.axelor.apps.message.db.MailAccount;
import com.axelor.apps.message.db.Message;
import com.axelor.apps.message.db.repo.EmailAddressRepository;
import com.axelor.apps.message.db.repo.MailAccountRepository;
import com.axelor.apps.message.db.repo.MessageRepository;
import com.axelor.apps.message.exception.IExceptionMessage;
import com.axelor.apps.tool.date.DateTool;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.i18n.I18n;
import com.axelor.mail.ImapAccount;
import com.axelor.mail.MailConstants;
import com.axelor.mail.MailParser;
import com.axelor.mail.MailReader;
import com.axelor.mail.Pop3Account;
import com.axelor.mail.SmtpAccount;
import com.axelor.meta.MetaFiles;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class MailAccountServiceImpl implements MailAccountService {
	
	private final Logger log = LoggerFactory.getLogger(MailAccountServiceImpl.class);
	
	static final int CHECK_CONF_TIMEOUT = 5000;
	
	@Inject
	protected MailAccountRepository mailAccountRepo;
	
	@Inject
	protected EmailAddressRepository emailAddressRepo;
	
	@Inject
	private MessageRepository messageRepo;
	
	@Inject
	private MetaFiles metaFiles;
	
	@Override
	public boolean checkDefaultMailAccount(MailAccount mailAccount) {
		if(mailAccount.getIsDefault()){
			String request = "self.isDefault = true";
			List<Object> params = Lists.newArrayList();
			if(mailAccount.getId() != null){
				request += " AND self.id != ?1";
				params.add(mailAccount.getId());
			}
			return mailAccountRepo.all().filter(request, params.toArray()).count() == 0;
		}
		return true;
	}

	@Override
	public MailAccount getDefaultMailAccount(int serverType)  {
		return mailAccountRepo.all().filter("self.isDefault = true and self.serverTypeSelect = ?1", serverType).fetchOne();
	}
   
	@Override
	public void checkMailAccountConfiguration ( MailAccount mailAccount ) throws AxelorException, Exception {
		
		String port = mailAccount.getPort() <= 0 ? null : mailAccount.getPort().toString();
		
		com.axelor.mail.MailAccount account;
		Integer serverType = mailAccount.getServerTypeSelect();
		if (serverType.equals(MailAccountRepository.SERVER_TYPE_SMTP)) {
			account = new SmtpAccount( mailAccount.getHost(), port, mailAccount.getLogin(), mailAccount.getPassword(), getSecurity( mailAccount ) );
		}
		else if (serverType.equals(MailAccountRepository.SERVER_TYPE_IMAP)) {
			account = new ImapAccount( mailAccount.getHost(), mailAccount.getPort().toString(), mailAccount.getLogin(), mailAccount.getPassword(), getSecurity(mailAccount) );
		}
		else {
			account = new Pop3Account( mailAccount.getHost(), mailAccount.getPort().toString(), mailAccount.getLogin(), mailAccount.getPassword(), getSecurity(mailAccount) );
		}
		
		account.setConnectionTimeout( CHECK_CONF_TIMEOUT );
		
		Session session = account.getSession();
		
		try {
			
			if (serverType.equals(MailAccountRepository.SERVER_TYPE_SMTP)) {
				Transport transport = session.getTransport( getProtocol( mailAccount ) );
				transport.connect( mailAccount.getHost(),mailAccount.getPort(),mailAccount.getLogin(),mailAccount.getPassword() );
				transport.close();
			}
			else {
				session.getStore().connect();
			}
			
		} catch ( AuthenticationFailedException e ) {
			throw new AxelorException(e, mailAccount, IException.CONFIGURATION_ERROR, I18n.get(IExceptionMessage.MAIL_ACCOUNT_1));
		} catch ( NoSuchProviderException e ) {
			throw new AxelorException(e, mailAccount, IException.CONFIGURATION_ERROR, I18n.get(IExceptionMessage.MAIL_ACCOUNT_2));
		}

	}
	
	
	public String getSecurity(MailAccount mailAccount)  {
		
		if ( mailAccount.getSecuritySelect() == MailAccountRepository.SECURITY_SSL ) { return MailConstants.CHANNEL_SSL; }
		else if (mailAccount.getSecuritySelect() == MailAccountRepository.SECURITY_STARTTLS ) { return MailConstants.CHANNEL_STARTTLS; }
		else { return null; }
		
	}
	
	public String getProtocol(MailAccount mailAccount) {
		
		switch ( mailAccount.getServerTypeSelect() ) {
		case MailAccountRepository.SERVER_TYPE_SMTP:
			return "smtp";
		case MailAccountRepository.SERVER_TYPE_IMAP:
			if (mailAccount.getSecuritySelect() == MailAccountRepository.SECURITY_SSL ){
				return MailConstants.PROTOCOL_IMAPS;
			}
			return MailConstants.PROTOCOL_IMAP;
		case MailAccountRepository.SERVER_TYPE_POP:
			return MailConstants.PROTOCOL_POP3;
		default:
			return "";
		}
		
	}
	
	public String getSignature(MailAccount mailAccount)  {
		
		if ( mailAccount != null && mailAccount.getSignature() != null ) { return "\n "+mailAccount.getSignature();	}		
		return "";
		
	}
	
	@Override
	public int fetchEmails(MailAccount mailAccount, boolean unseenOnly) throws MessagingException, IOException {
		
		if (mailAccount == null) { return 0; }
		
		log.debug("Fetching emails from host: {}, port: {}, login: {} ", mailAccount.getHost(), mailAccount.getPort(), mailAccount.getLogin());
		
		com.axelor.mail.MailAccount account = null;
		if (mailAccount.getServerTypeSelect().equals(MailAccountRepository.SERVER_TYPE_IMAP)) {
			account = new ImapAccount( mailAccount.getHost(), mailAccount.getPort().toString(), mailAccount.getLogin(), mailAccount.getPassword(), getSecurity(mailAccount) );
		}
		else {
			account = new Pop3Account( mailAccount.getHost(), mailAccount.getPort().toString(), mailAccount.getLogin(), mailAccount.getPassword(), getSecurity(mailAccount) );
		}
		
		MailReader reader = new MailReader(account);
		final Store store = reader.getStore();
		final Folder inbox = store.getFolder("INBOX");

		// open as READ_WRITE to mark messages as seen
		inbox.open(Folder.READ_WRITE);

		// find all unseen messages
		final FetchProfile profile = new FetchProfile();
		javax.mail.Message[] messages;
		if (unseenOnly) {
			final FlagTerm unseen = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
			messages = inbox.search(unseen);
		}
		else {
			messages = inbox.getMessages();
		}

		profile.add(FetchProfile.Item.ENVELOPE);

		// actually fetch the messages
		inbox.fetch(messages, profile);
		log.debug("Total emails unseen: {}", messages.length);
		
		int count = 0;
		for (javax.mail.Message message : messages) {
			if (message instanceof MimeMessage) { 
				MailParser parser = new MailParser((MimeMessage) message);
				parser.parse();
				createMessage(mailAccount, parser, message.getSentDate());
				count++;
			}
		}
		
		log.debug("Total emails fetched: {}", count);
		
		return count;
	}
	
	@Transactional
	public Message createMessage(MailAccount mailAccount, MailParser parser, Date date) throws MessagingException, IOException {
		
		Message message = new Message();
		
		message.setMailAccount(mailAccount);
		message.setTypeSelect(MessageRepository.TYPE_SENT);
		message.setMediaTypeSelect(MessageRepository.MEDIA_TYPE_EMAIL);
		
		message.setFromEmailAddress(getEmailAddress(parser.getFrom()));
		message.setCcEmailAddressSet(getEmailAddressSet(parser.getCc()));
		message.setBccEmailAddressSet(getEmailAddressSet(parser.getBcc()));
		message.setToEmailAddressSet(getEmailAddressSet(parser.getTo()));
		message.addReplyToEmailAddressSetItem(getEmailAddress(parser.getReplyTo()));
		
		message.setContent(parser.getHtml());
		message.setSubject(parser.getSubject());
		message.setSentDateT(DateTool.toLocalDateT(date));
		
		message = messageRepo.save(message);
		
		List<DataSource> attachments = parser.getAttachments();
		addAttachments(message, attachments);
		
		return message;
	}
	
	private EmailAddress getEmailAddress(InternetAddress address) {
		
		EmailAddress emailAddress = null;
		emailAddress = emailAddressRepo.findByAddress(address.getAddress());
		if (emailAddress == null) {
			emailAddress = new EmailAddress();
			emailAddress.setAddress(address.getAddress());
		}
		
		return emailAddress;
	}
	
	private Set<EmailAddress> getEmailAddressSet(List<InternetAddress> addresses) {
		
		Set<EmailAddress> addressSet = new HashSet<EmailAddress>();
		
		if (addresses == null) {
			return addressSet;
		}
		
		for (InternetAddress address : addresses) {
			
			EmailAddress emailAddress = getEmailAddress(address);
			
			addressSet.add(emailAddress);
		}
		
		return addressSet;
	}
	
	private void addAttachments(Message message, List<DataSource> attachments) {
		
		if (attachments == null) {
			return;
		}
		
		for (DataSource source : attachments) {
			try {
				InputStream stream = source.getInputStream();
				metaFiles.attach(stream, source.getName(), message);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
