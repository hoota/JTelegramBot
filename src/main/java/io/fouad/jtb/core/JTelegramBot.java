/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Fouad Almalki
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.fouad.jtb.core;

import com.fasterxml.jackson.core.type.TypeReference;
import io.fouad.jtb.core.TelegramBotConfig.TelegramBotConfigBuilder;
import io.fouad.jtb.core.beans.BooleanOrMessageResult;
import io.fouad.jtb.core.beans.CallbackQuery;
import io.fouad.jtb.core.beans.Chat;
import io.fouad.jtb.core.beans.ChatIdentifier;
import io.fouad.jtb.core.beans.ChatMember;
import io.fouad.jtb.core.beans.ChosenInlineResult;
import io.fouad.jtb.core.beans.InlineKeyboardMarkup;
import io.fouad.jtb.core.beans.InlineQuery;
import io.fouad.jtb.core.beans.InlineQueryResult;
import io.fouad.jtb.core.beans.MediaIdentifier;
import io.fouad.jtb.core.beans.Message;
import io.fouad.jtb.core.beans.ReplyMarkup;
import io.fouad.jtb.core.beans.TelegramFile;
import io.fouad.jtb.core.beans.TelegramResult;
import io.fouad.jtb.core.beans.Update;
import io.fouad.jtb.core.beans.User;
import io.fouad.jtb.core.beans.UserProfilePhotos;
import io.fouad.jtb.core.enums.BotState;
import io.fouad.jtb.core.enums.ChatAction;
import io.fouad.jtb.core.enums.ParseMode;
import io.fouad.jtb.core.exceptions.NegativeResponseException;
import io.fouad.jtb.core.utils.HttpClient;
import io.fouad.jtb.core.utils.HttpClient.FileField;
import io.fouad.jtb.core.utils.HttpClient.HttpResponse;
import io.fouad.jtb.core.utils.HttpClient.NameValueParameter;
import io.fouad.jtb.core.utils.JsonUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a Telegram bot. It supports POLLING mode by default. To run as webhook,
 * use this class with <code>WebhookServer</code> or use your custom server.
 */
public class JTelegramBot implements TelegramBotApi
{
	final Log log = LogFactory.getLog(this.getClass());
	private final String botName;
	private final String apiToken;
	private final UpdateHandler updateHandler;
	
	// an atomic flag to indicate the current running state of the bot
	private AtomicReference<BotState> botState = new AtomicReference<>(BotState.IDLE);
	
	/**
	 * Creates an instance of Telegram bot.
	 * 
	 * @param botName it is used only for debugging purposes
	 * @param apiToken the API token which is usually retrieved from @BotFather (cannot be null) 
	 * @param updateHandler the handler which handles incoming updates to the bot
	 */
	public JTelegramBot(String botName, String apiToken, UpdateHandler updateHandler)
	{
		if(apiToken == null) throw new IllegalArgumentException("\"apiToken\" cannot be null.");
		
		this.botName = botName;
		this.apiToken = apiToken;
		this.updateHandler = updateHandler;
	}
	
	@Override
	public String getApiToken(){return apiToken;}
	public String getBotName(){return botName;}
	
	/**
	 * Starts the bot in POLLING mode. This is a blocking method.
	 */
	public void start()
	{
		if(!botState.compareAndSet(BotState.IDLE, BotState.STARTING))
		{
			throw new IllegalStateException("You cannot start the bot while it is not idle.");
		}
		
		start(new TelegramBotConfigBuilder().build());
	}
	
	/**
	 * Starts the bot in POLLING mode. This method returns immediately.
	 */
	public void startAsync()
	{
		if(!botState.compareAndSet(BotState.IDLE, BotState.STARTING))
		{
			throw new IllegalStateException("You cannot start the bot while it is not idle.");
		}
		
		startAsync(new TelegramBotConfigBuilder().build());
	}
	
	/**
	 * Stops the bot at next timeout.
	 */
	public void stop()
	{
		if(!botState.compareAndSet(BotState.RUNNING, BotState.STOPPING))
		{
			throw new IllegalStateException("You cannot stop the bot while it is not running.");
		}
	}
	
	/**
	 * Starts the bot in POLLING mode, with passing custom configurations. This is a blocking method.
	 * 
	 * @param telegramBotConfig custom configurations related to the bot
	 */
	public void start(TelegramBotConfig telegramBotConfig) throws IllegalStateException
	{
		startPolling(telegramBotConfig);
	}
	
	/**
	 * Starts the bot in POLLING mode, with passing custom configurations. This method returns immediately.
	 *
	 * @param telegramBotConfig custom configurations related to the bot
	 */
	public void startAsync(final TelegramBotConfig telegramBotConfig) throws IllegalStateException
	{
		new Thread(() -> startPolling(telegramBotConfig)).start();
	}
	
	/**
	 * Starts the bot in POLLING mode, with passing custom configurations.
	 * 
	 * @param telegramBotConfig custom configurations related to the bot
	 */
	private void startPolling(TelegramBotConfig telegramBotConfig)
	{
		HttpClient.init(telegramBotConfig);

		Integer offset = null;
		int timeout = telegramBotConfig.getPollingTimeoutInSeconds();
		
		botState.set(BotState.RUNNING);
		log.info("JTelegramBot (" + botName + ") starts in \"Polling\" mode.");
		
		while(botState.get() == BotState.RUNNING)
		{
			try
			{
				List<Update> newUpdates = getNewUpdates(offset, timeout);
				
				for(int i = 0; i < newUpdates.size(); i++)
				{
					final Update newUpdate = newUpdates.get(i);
					
					telegramBotConfig.getExecutorService().submit(new Runnable() {
						@Override
						public void run()
						{
							onUpdateReceived(newUpdate);
						}
					});
					
					if(i == newUpdates.size() - 1) // if last item, calculate the offset
					{
						offset = newUpdate.getUpdateId() + 1;
					}
				}
			}
			catch(Exception e)
			{
				if(updateHandler != null) updateHandler.onGetUpdatesFailure(e);
			}
		}
	}
	
	/**
	 * This method is invoked by a worker thread upon receiving a new update.
	 * 
	 * @param update the new update object to be handled
	 */
	public void onUpdateReceived(Update update)
	{
		int updateId = update.getUpdateId();
		Message message = update.getMessage();
		Message editedMessage = update.getEditedMessage();
		InlineQuery inlineQuery = update.getInlineQuery();
		ChosenInlineResult chosenInlineResult = update.getChosenInlineResult();
		CallbackQuery callbackQuery = update.getCallbackQuery();
		
		if(updateHandler != null)
		{
			if(message != null) updateHandler.onMessageReceived(this, updateId, message);
			else if(editedMessage != null) updateHandler.onEditedMessageReceived(this, updateId, editedMessage);
			else if(inlineQuery != null) updateHandler.onInlineQueryReceived(this, updateId, inlineQuery);
			else if(chosenInlineResult != null) updateHandler.onChosenInlineResultReceived(this, updateId, chosenInlineResult);
			else if(callbackQuery != null) updateHandler.onCallbackQueryReceived(this, updateId, callbackQuery);
		}
	}
	
	/**
	 * Register a webhook to receive new updates from Telegram server on the specified <code>listenUrl</code>.
	 * The incoming updates are sent as HTTPS POST request.
	 * 
	 * @param listenUrl HTTPS url to send updates to
	 * @param certificateFile the public key certificate to instantiate an HTTPS connection to the server that listens
	 *                        to <code>listenUrl</code>. The certificate supplied should be PEM encoded (ASCII BASE64),
	 *                        the pem file should only contain the public key (including BEGIN and END portions)
	 *                        
	 * @return response from Telegram server to the webhook request
	 * 
	 * @throws IOException if an I/O exception occurs
	 * @throws NegativeResponseException if 4xx-5xx HTTP response is received from Telegram server
	 */
	public TelegramResult<String> registerWebhook(String listenUrl, File certificateFile) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		List<NameValueParameter<String, FileField>> files = new ArrayList<>();
		
		formFields.add(new NameValueParameter<>("url", listenUrl));
		files.add(new NameValueParameter<>("certificate", new FileField(certificateFile)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/setWebhook", formFields, files);
		TelegramResult<String> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<String>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult;
	}
	
	/**
	 * Unregister the webhook if exists.
	 *
	 * @return response from Telegram server to the webhook removing request
	 *
	 * @throws IOException if an I/O exception occurs
	 * @throws NegativeResponseException if 4xx-5xx HTTP response is received from Telegram server
	 */
	public TelegramResult<String> unregisterWebhook() throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		List<NameValueParameter<String, FileField>> files = new ArrayList<>();
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/setWebhook", formFields, files);
		TelegramResult<String> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<String>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult;
	}
	
	/**
	 * This method is used to receive incoming updates using long polling.
	 * 
	 * @param offset identifier of the first update to be returned. Must be greater by one than the highest among
	 *               the identifiers of previously received updates. By default, updates starting with the earliest
	 *               unconfirmed update are returned. An update is considered confirmed as soon as getUpdates is
	 *               called with an offset higher than its update_id. The negative offset can be specified to
	 *               retrieve updates starting from -offset update from the end of the updates queue. All previous
	 *               updates will forgotten.
	 * @param timeout timeout in seconds for long polling. Defaults to 0, i.e. usual short polling
	 * 
	 * @return list of <code>Update</code> objects. If no new update, an empty list is returned 
	 * 
	 * @throws IOException if an I/O exception occurs
	 * @throws NegativeResponseException if 4xx-5xx HTTP response is received from Telegram server
	 */
	private List<Update> getNewUpdates(Integer offset, int timeout) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		if(offset != null) formFields.add(new NameValueParameter<>("offset", String.valueOf(offset)));
		formFields.add(new NameValueParameter<>("timeout", String.valueOf(timeout)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getUpdates", formFields);
		TelegramResult<Update[]> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Update[]>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return Arrays.asList(telegramResult.getResult());
	}
	
	/*============ API METHODS IMPLEMENTATION ============*/
	
	@Override
	public User getMe() throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getMe", formFields);
		TelegramResult<User> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<User>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendMessage(ChatIdentifier targetChatIdentifier, String text, ParseMode parseMode,
	              Boolean disableLinkPreviews, Boolean silentMessage, Integer replyToMessageId, ReplyMarkup replyMarkup)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		formFields.add(new NameValueParameter<>("text", text));
		if(parseMode != null) formFields.add(new NameValueParameter<>("parse_mode", String.valueOf(parseMode)));
		if(disableLinkPreviews != null) formFields.add(new NameValueParameter<>("disable_web_page_preview", String.valueOf(disableLinkPreviews)));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendMessage", formFields);
		
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message forwardMessage(ChatIdentifier targetChatIdentifier, ChatIdentifier sourceChatIdentifier,
	                              Boolean silentMessage, Integer messageId) throws IOException,
			NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		username = sourceChatIdentifier.getUsername();
		id = sourceChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("from_chat_id", username));
		else formFields.add(new NameValueParameter<>("from_chat_id", String.valueOf(id)));
		
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		formFields.add(new NameValueParameter<>("message_id", String.valueOf(messageId)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/forwardMessage", formFields);
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendPhoto(ChatIdentifier targetChatIdentifier, MediaIdentifier mediaIdentifier, String photoCaption,
	                         Boolean silentMessage, Integer replyToMessageId, ReplyMarkup replyMarkup)
			throws IOException, NegativeResponseException
	{
		String mediaId = mediaIdentifier.getMediaId();
		
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		if(mediaId != null) formFields.add(new NameValueParameter<>("photo", mediaId));
		if(photoCaption != null) formFields.add(new NameValueParameter<>("caption", photoCaption));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response;
		
		if(mediaId == null)
		{
			List<NameValueParameter<String, FileField>> files = new ArrayList<>();
			files.add(new NameValueParameter<>("photo", new FileField(mediaIdentifier.getFileName(), mediaIdentifier.getMediaInputStream())));
			
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendPhoto", formFields, files);
		}
		else
		{
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendPhoto", formFields);
		}
		
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendAudio(ChatIdentifier targetChatIdentifier, MediaIdentifier mediaIdentifier, Integer duration,
	                         String performer, String trackTitle, Boolean silentMessage, Integer replyToMessageId,
	                         ReplyMarkup replyMarkup) throws IOException, NegativeResponseException
	{
		String mediaId = mediaIdentifier.getMediaId();
		
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		if(mediaId != null) formFields.add(new NameValueParameter<>("audio", mediaId));
		if(duration != null) formFields.add(new NameValueParameter<>("duration", String.valueOf(duration)));
		if(performer != null) formFields.add(new NameValueParameter<>("performer", performer));
		if(trackTitle != null) formFields.add(new NameValueParameter<>("title", trackTitle));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response;
		
		if(mediaId == null)
		{
			List<NameValueParameter<String, FileField>> files = new ArrayList<>();
			files.add(new NameValueParameter<>("audio", new FileField(mediaIdentifier.getFileName(), mediaIdentifier.getMediaInputStream())));
			
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendAudio", formFields, files);
		}
		else
		{
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendAudio", formFields);
		}
				
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendDocument(ChatIdentifier targetChatIdentifier, MediaIdentifier mediaIdentifier,
	                            String documentCaption, Boolean silentMessage, Integer replyToMessageId,
	                            ReplyMarkup replyMarkup) throws IOException, NegativeResponseException
	{
		String mediaId = mediaIdentifier.getMediaId();
		
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		if(mediaId != null) formFields.add(new NameValueParameter<>("document", mediaId));
		if(documentCaption != null) formFields.add(new NameValueParameter<>("caption", String.valueOf(documentCaption)));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response;
		
		if(mediaId == null)
		{
			List<NameValueParameter<String, FileField>> files = new ArrayList<>();
			files.add(new NameValueParameter<>("document", new FileField(mediaIdentifier.getFileName(), mediaIdentifier.getMediaInputStream())));
			
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendDocument", formFields, files);
		}
		else
		{
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendDocument", formFields);
		}
		
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendSticker(ChatIdentifier targetChatIdentifier, MediaIdentifier mediaIdentifier,
	                           Boolean silentMessage, Integer replyToMessageId, ReplyMarkup replyMarkup)
			throws IOException, NegativeResponseException
	{
		String mediaId = mediaIdentifier.getMediaId();
		
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		if(mediaId != null) formFields.add(new NameValueParameter<>("sticker", mediaId));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response;
		
		if(mediaId == null)
		{
			List<NameValueParameter<String, FileField>> files = new ArrayList<>();
			files.add(new NameValueParameter<>("sticker", new FileField(mediaIdentifier.getFileName(), mediaIdentifier.getMediaInputStream())));
			
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendSticker", formFields, files);
		}
		else
		{
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendSticker", formFields);
		}
				
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendVideo(ChatIdentifier targetChatIdentifier, MediaIdentifier mediaIdentifier, Integer duration,
	                         Integer width, Integer height, String videoCaption, Boolean silentMessage,
	                         Integer replyToMessageId, ReplyMarkup replyMarkup)
			throws IOException, NegativeResponseException
	{
		String mediaId = mediaIdentifier.getMediaId();
		
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		if(mediaId != null) formFields.add(new NameValueParameter<>("video", mediaId));
		if(duration != null) formFields.add(new NameValueParameter<>("duration", String.valueOf(duration)));
		if(width != null) formFields.add(new NameValueParameter<>("width", String.valueOf(width)));
		if(height != null) formFields.add(new NameValueParameter<>("height", String.valueOf(height)));
		if(videoCaption != null) formFields.add(new NameValueParameter<>("caption", videoCaption));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response;
		
		if(mediaId == null)
		{
			List<NameValueParameter<String, FileField>> files = new ArrayList<>();
			files.add(new NameValueParameter<>("video", new FileField(mediaIdentifier.getFileName(), mediaIdentifier.getMediaInputStream())));
			
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendVideo", formFields, files);
		}
		else
		{
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendVideo", formFields);
		}
		
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendVoice(ChatIdentifier targetChatIdentifier, MediaIdentifier mediaIdentifier, Integer duration,
	                         Boolean silentMessage, Integer replyToMessageId, ReplyMarkup replyMarkup)
			throws IOException, NegativeResponseException
	{
		String mediaId = mediaIdentifier.getMediaId();
		
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		if(mediaId != null) formFields.add(new NameValueParameter<>("voice", mediaId));
		if(duration != null) formFields.add(new NameValueParameter<>("duration", String.valueOf(duration)));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response;
		
		if(mediaId == null)
		{
			List<NameValueParameter<String, FileField>> files = new ArrayList<>();
			files.add(new NameValueParameter<>("voice", new FileField(mediaIdentifier.getFileName(), mediaIdentifier.getMediaInputStream())));
			
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendVoice", formFields, files);
		}
		else
		{
			response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendVoice", formFields);
		}
				
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendLocation(ChatIdentifier targetChatIdentifier, float latitude, float longitude,
	                            Boolean silentMessage, Integer replyToMessageId, ReplyMarkup replyMarkup)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		formFields.add(new NameValueParameter<>("latitude", String.valueOf(latitude)));
		formFields.add(new NameValueParameter<>("longitude", String.valueOf(longitude)));
		if(silentMessage != null) formFields.add(new NameValueParameter<>("disable_notification", String.valueOf(silentMessage)));
		if(replyToMessageId != null) formFields.add(new NameValueParameter<>("reply_to_message_id", String.valueOf(replyToMessageId)));
		if(replyMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(replyMarkup)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendLocation", formFields);
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Message sendVenue(ChatIdentifier targetChatIdentifier, float latitude, float longitude, String title,
	                         String address, String foursquareId, Boolean silentMessage, Integer replyToMessageId,
	                         ReplyMarkup replyMarkup) throws IOException, NegativeResponseException
	{
		return null;
	}
	
	@Override
	public Message sendContact(ChatIdentifier targetChatIdentifier, String phoneNumber, String firstName,
	                           String lastName, Boolean silentMessage, Integer replyToMessageId,
	                           ReplyMarkup replyMarkup) throws IOException, NegativeResponseException
	{
		return null;
	}
	
	@Override
	public void sendChatAction(ChatIdentifier targetChatIdentifier, ChatAction action) throws IOException,
			NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		formFields.add(new NameValueParameter<>("action", String.valueOf(action)));
		
		HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/sendChatAction", formFields);
	}
	
	@Override
	public UserProfilePhotos getUserProfilePhotos(int userId, Integer offset, Integer limit) throws IOException,
			NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		formFields.add(new NameValueParameter<>("user_id", String.valueOf(userId)));
		if(offset != null) formFields.add(new NameValueParameter<>("offset", String.valueOf(offset)));
		if(limit != null) formFields.add(new NameValueParameter<>("limit", String.valueOf(limit)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getUserProfilePhotos", formFields);
		TelegramResult<UserProfilePhotos> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<UserProfilePhotos>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public TelegramFile getFile(String fileId) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		formFields.add(new NameValueParameter<>("file_id", fileId));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getFile", formFields);
		TelegramResult<TelegramFile> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<TelegramFile>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public boolean kickChatMember(ChatIdentifier targetChatIdentifier, int userId)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		formFields.add(new NameValueParameter<>("user_id", String.valueOf(userId)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/kickChatMember", formFields);
		TelegramResult<Boolean> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Boolean>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public boolean leaveChat(ChatIdentifier targetChatIdentifier) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/leaveChat", formFields);
		TelegramResult<Boolean> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Boolean>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public boolean unbanChatMember(ChatIdentifier targetChatIdentifier, int userId)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		formFields.add(new NameValueParameter<>("user_id", String.valueOf(userId)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/unbanChatMember", formFields);
		TelegramResult<Boolean> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Boolean>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public Chat getChat(ChatIdentifier targetChatIdentifier) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getChat", formFields);
		TelegramResult<Chat> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Chat>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public ChatMember[] getChatAdministrators(ChatIdentifier targetChatIdentifier) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getChatAdministrators", formFields);
		TelegramResult<ChatMember[]> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<ChatMember[]>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public int getChatMembersCount(ChatIdentifier targetChatIdentifier) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getChatMembersCount", formFields);
		TelegramResult<Integer> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Integer>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public ChatMember getChatMember(ChatIdentifier targetChatIdentifier, int userId) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		String username = targetChatIdentifier.getUsername();
		Long id = targetChatIdentifier.getId();
		if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
		else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		
		formFields.add(new NameValueParameter<>("user_id", String.valueOf(userId)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/getChatMember", formFields);
		TelegramResult<ChatMember> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<ChatMember>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public boolean answerCallbackQuery(String callbackQueryId, String text, Boolean showAlert)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		formFields.add(new NameValueParameter<>("callback_query_id", callbackQueryId));
		if(text != null) formFields.add(new NameValueParameter<>("text", text));
		if(showAlert != null) formFields.add(new NameValueParameter<>("show_alert", String.valueOf(showAlert)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/answerCallbackQuery", formFields);
		TelegramResult<Boolean> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Boolean>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
	
	@Override
	public BooleanOrMessageResult editMessageText(ChatIdentifier targetChatIdentifier, Integer messageId, String inlineMessageId,
	                               String text, ParseMode parseMode, Boolean disableLinkPreviews,
	                               InlineKeyboardMarkup inlineKeyboardMarkup)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		if(targetChatIdentifier != null)
		{
			String username = targetChatIdentifier.getUsername();
			Long id = targetChatIdentifier.getId();
			if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
			else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		}
		
		if(messageId != null) formFields.add(new NameValueParameter<>("message_id", String.valueOf(messageId)));
		if(inlineMessageId != null) formFields.add(new NameValueParameter<>("inline_message_id", inlineMessageId));
		formFields.add(new NameValueParameter<>("text", String.valueOf(text)));
		if(parseMode != null) formFields.add(new NameValueParameter<>("parse_mode", String.valueOf(parseMode)));
		if(disableLinkPreviews != null) formFields.add(new NameValueParameter<>("disable_web_page_preview", String.valueOf(disableLinkPreviews)));
		if(inlineKeyboardMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(inlineKeyboardMarkup)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/editMessageText", formFields);
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return new BooleanOrMessageResult(telegramResult.getResult());
	}

	@Override
	public BooleanOrMessageResult editMessageCaption(ChatIdentifier targetChatIdentifier, Integer messageId, String inlineMessageId,
	                                  String caption, InlineKeyboardMarkup inlineKeyboardMarkup)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		if(targetChatIdentifier != null)
		{
			String username = targetChatIdentifier.getUsername();
			Long id = targetChatIdentifier.getId();
			if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
			else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		}
		
		if(messageId != null) formFields.add(new NameValueParameter<>("message_id", String.valueOf(messageId)));
		if(inlineMessageId != null) formFields.add(new NameValueParameter<>("inline_message_id", inlineMessageId));
		if(caption != null) formFields.add(new NameValueParameter<>("caption", caption));
		if(inlineKeyboardMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(inlineKeyboardMarkup)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/editMessageCaption", formFields);
		TelegramResult<String> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<String>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return new BooleanOrMessageResult(telegramResult.getResult());
	}
	
	@Override
	public BooleanOrMessageResult editMessageReplyMarkup(ChatIdentifier targetChatIdentifier, Integer messageId,
	                                      String inlineMessageId, InlineKeyboardMarkup inlineKeyboardMarkup)
			throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		if(targetChatIdentifier != null)
		{
			String username = targetChatIdentifier.getUsername();
			Long id = targetChatIdentifier.getId();
			if(username != null) formFields.add(new NameValueParameter<>("chat_id", username));
			else formFields.add(new NameValueParameter<>("chat_id", String.valueOf(id)));
		}
		
		if(messageId != null) formFields.add(new NameValueParameter<>("message_id", String.valueOf(messageId)));
		if(inlineMessageId != null) formFields.add(new NameValueParameter<>("inline_message_id", inlineMessageId));
		if(inlineKeyboardMarkup != null) formFields.add(new NameValueParameter<>("reply_markup", JsonUtils.toJson(inlineKeyboardMarkup)));
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/editMessageReplyMarkup", formFields);
		TelegramResult<Message> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Message>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return new BooleanOrMessageResult(telegramResult.getResult());
	}
	
	@Override
	public boolean answerInlineQuery(String inlineQueryId, InlineQueryResult[] results, Integer cacheTime,
	                                 Boolean isPersonal, String nextOffset, String switchPmText,
	                                 String switchPmParameter) throws IOException, NegativeResponseException
	{
		List<NameValueParameter<String, String>> formFields = new ArrayList<>();
		
		formFields.add(new NameValueParameter<>("inline_query_id", String.valueOf(inlineQueryId)));
		formFields.add(new NameValueParameter<>("results", JsonUtils.toJson(results)));
		if(cacheTime != null) formFields.add(new NameValueParameter<>("cache_time", String.valueOf(cacheTime)));
		if(isPersonal != null) formFields.add(new NameValueParameter<>("is_personal", String.valueOf(isPersonal)));
		if(nextOffset != null) formFields.add(new NameValueParameter<>("next_offset", nextOffset));
		if(switchPmText != null) formFields.add(new NameValueParameter<>("switch_pm_text", switchPmText));
		if(switchPmParameter != null) formFields.add(new NameValueParameter<>("switch_pm_parameter", switchPmParameter));
		
//		System.out.println(formFields);
		
		HttpResponse response = HttpClient.sendHttpPost(API_URL_PREFIX + apiToken + "/answerInlineQuery", formFields);
		TelegramResult<Boolean> telegramResult = JsonUtils.toJavaObject(response.getResponseBody(), new TypeReference<TelegramResult<Boolean>>(){});
		
		if(!telegramResult.isOk()) throw new NegativeResponseException(response.getHttpStatusCode(), telegramResult);
		
		return telegramResult.getResult();
	}
}