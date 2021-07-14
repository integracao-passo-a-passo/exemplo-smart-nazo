// camel-k: language=java dependency=camel-telegram dependency=camel-quarkus-http
// camel-k: dependency=camel-jackson dependency=camel-jsonpath dependency=camel-kafka

import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.PropertyInject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.apache.camel.component.telegram.model.InlineKeyboardButton;
import org.apache.camel.component.telegram.model.ReplyKeyboardMarkup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SmartNazoBot extends RouteBuilder {
	private static final Logger logger = LoggerFactory.getLogger(SmartNazoBot.class);

	// Países
	@PropertyInject("smart-nazo.countries")
	private String countries;


	@PropertyInject(value = "smart-nazo.welcome.command", defaultValue="/start")
	private String welcomeCommand;

	// Commando inicial
	@PropertyInject(value = "smart-nazo.start.command")
	private String startCommand;

	/**********
	 * Mensagens: configure-as no arquivo application.properties
	 **********/
	// Mensagem de boas vindas
	@PropertyInject("smart-nazo.msg.welcome")
	private String welcomeMessage;

	// Mensagem de inicio
	@PropertyInject(value = "smart-nazo.msg.start")
	private String startMessage;

	// Mensagem de comando inválido
	@PropertyInject("smart-nazo.msg.warning.invalid")
	private String invalidCommandMessage;

	// Mensagem de localização não encontrada
	@PropertyInject("smart-nazo.msg.warning.not-found")
	private String invalidLocation;

	// Mensagem enviada com os valores de poluição do ar
	@PropertyInject("smart-nazo.msg.values")
	private String messageValues;

	private final Map<String, List<OutgoingTextMessage>> cache = new HashMap<>();

	private InlineKeyboardButton buttonBuilder(Object object) {
		return InlineKeyboardButton.builder().text(object.toString()).build();
	}

	private InlineKeyboardButton resetButton() {
		return InlineKeyboardButton.builder().text(startCommand).build();
	}

	// Processa o comando inicial e configura o botão com a lista de países
	private void processStart(Exchange exchange) {
		OutgoingTextMessage msg = new OutgoingTextMessage();

		// Cria uma lista de botões, uma para cada país
		List<InlineKeyboardButton> buttons = Arrays.stream(countries.split(","))
				.map(c -> buttonBuilder(c))
				.collect(Collectors.toList());

		// O botão de começo sempre no início da lista
		buttons.add(0, resetButton());

		ReplyKeyboardMarkup replyMarkup = ReplyKeyboardMarkup.builder()
			.keyboard()
				.addOneRowByEachButton(buttons)
				.close()
				.oneTimeKeyboard(false)
				.build();

		msg.setReplyMarkup(replyMarkup);
		msg.setText(welcomeMessage);

		exchange.getIn().setBody(msg);
	}


	private void processCity(Exchange exchange, List<?> cities) {
		if (cities == null || cities.isEmpty()) {
			exchange.getIn().setBody(invalidLocation);
			return;
		}

		OutgoingTextMessage msg = new OutgoingTextMessage();

		// Lista de botões, uma para cada cidade
		List<InlineKeyboardButton> buttons = cities.stream()
			.map(this::buttonBuilder)
			.collect(Collectors.toList());

		buttons.add(0, resetButton());

		ReplyKeyboardMarkup replyMarkup = ReplyKeyboardMarkup.builder()
			.keyboard()
				.addOneRowByEachButton(buttons)
				.close()
			.oneTimeKeyboard(false)
			.build();

		msg.setReplyMarkup(replyMarkup);
		msg.setText(startMessage);

		exchange.getIn().setBody(msg);
	}


	private void processCountryCitiesText(Exchange exchange) throws IOException {
		List<?> body = exchange.getMessage().getBody(List.class);

		String country = exchange.getMessage().getHeader("country", String.class);

		// Para cada cidade, muda o texto para o formato cidade@país.
		List<String> cities = body.stream()
			.map(o -> o + "@" + country)
			.collect(Collectors.toList());

		// Processa botões e resposta
		processCity(exchange, cities);
	}

	private void saveToCache(String country, String city, OutgoingTextMessage msg) {
		cache.computeIfAbsent(fullLocation(country, city), k -> new ArrayList<>()).add(msg);
	}

	private String fullLocation(String country, String city) {
		return city + "@" + country;
	}


	// Pega cidades pré-existentes no cache
	private void processCitiesTextFromCache(Exchange exchange) throws IOException {
		List<OutgoingTextMessage> cachedMessages = cache.get(exchange.getMessage().getBody(String.class));

		exchange.getMessage().setBody(cachedMessages);
	}

	private void processCitiesText(Exchange exchange) throws IOException {
		String value = exchange.getMessage().getBody(String.class);

		String parameter = exchange.getMessage().getHeader("parameter", String.class);
		String unit = exchange.getMessage().getHeader("unit", String.class);
		String city = exchange.getMessage().getHeader("city", String.class);
		String country = exchange.getMessage().getHeader("country", String.class);

		// Telegram reclama de ter o ponto em alguns valores
		String text = String.format(messageValues, parameter, city, value.replace(".", ","), unit);

		logger.info(text);

		// Formata a mensagem de reposta
		OutgoingTextMessage msg = OutgoingTextMessage.builder()
			.parseMode("MarkdownV2")
			.text(text)
			.build();

		// Insere o botão de início
		List<InlineKeyboardButton> buttons = new ArrayList<>();
		buttons.add(resetButton());

		ReplyKeyboardMarkup replyMarkup = ReplyKeyboardMarkup.builder()
			.keyboard()
				.addOneRowByEachButton(buttons)
				.close()
			.oneTimeKeyboard(true)
			.resizeKeyboard(true)
			.build();

		msg.setReplyMarkup(replyMarkup);
		saveToCache(country, city, msg);

		exchange.getMessage().setBody(msg);
	}

	// Formata manualmente os alertas em formato JSON para mandar para o Kafka
	private void processCitiesAlerts(Exchange exchange) throws IOException {
		String value = exchange.getMessage().getBody(String.class);

		String parameter = exchange.getMessage().getHeader("parameter", String.class);
		String unit = exchange.getMessage().getHeader("unit", String.class);
		String city = exchange.getMessage().getHeader("city", String.class);
		String country = exchange.getMessage().getHeader("country", String.class);

		String text = String.format("{ \"country\": \"%s\", \"city\": \"%s\", \"parameter\": \"%s\", \"value\": \"%s %s\"}",
				country, city, parameter, value, unit);

		exchange.getMessage().setBody(text);
	}


	public static String getCitiesInCountry(String body) {
		logger.debug("Computing the router for country {}", body);
		return "{{smart-nazo.openaq.base.url}}/cities?country=" + body;
	}

	private Predicate isCityCached() {
		return p -> {
			String body = p.getMessage().getBody(String.class);
			logger.debug("Checking if {} is cached", body);

			return cache.containsKey(body);
		};
	}

	public static String refreshLastMeasurementForCity(String body) {
		logger.debug("Computing the route for city {}", body);

		String city = body.split("@")[0];
		String country = body.split("@")[1];

		return String.format("{{smart-nazo.openaq.base.url}}/latest?city=%s&country=%s", city, country);
	}

	private Predicate isKnownCountry() {
		return p -> Arrays.asList(countries.split(",")).contains(p.getMessage().getBody(String.class));
	}

	private Predicate isKnownCity() {
		return p -> p.getMessage().getBody(String.class).contains("@") ;
	}


	@Override
	public void configure() throws Exception {
		// Logging
		from("direct:openaq-tap")
			.log("${body}");

		// Bot
		from("telegram:bots?authorizationToken={{smart-nazo.telegram.token}}")
			.wireTap("direct:openaq-tap")
			.choice()
				.when(body().isEqualTo(welcomeCommand))
					.process(this::processStart)
					.to("direct:telegram")
				.when(body().isEqualTo(startCommand))
					.process(this::processStart)
					.to("direct:telegram")
				.when(body().in(isKnownCountry()))
					.to("direct:destinationCountryResolver")
				.when(body().in(isKnownCity()))
					.to("direct:queryCityMeasurements")
				.otherwise()
					.setBody(constant(invalidCommandMessage))
					.to("direct:telegram");

		// Cidades
		from("direct:destinationCountryResolver")
			.streamCaching()
			.routingSlip(method(SmartNazoBot.class, "getCitiesInCountry"))
			.setHeader("country", jsonpath("$.results[0].country"))
			.setBody(jsonpath("$.results[*].city"))
			.process(this::processCountryCitiesText)
			.to("direct:telegram");

		// Medições: rota pai
		from("direct:queryCityMeasurements")
			.streamCaching()
			.choice()
			.when(isCityCached())
				.process(this::processCitiesTextFromCache)
				.to("direct:cached")
			.otherwise()
				.routingSlip(method(SmartNazoBot.class, "refreshLastMeasurementForCity"))
				.setHeader("country", jsonpath("$.results[0].country"))
				.setHeader("city", jsonpath("$.results[0].city"))
				.split().jsonpathWriteAsString("$.results[*].measurements[*]")
				.multicast().parallelProcessing().to("direct:measurements", "direct:alerts");

		// Medições: dados do cache
		from("direct:cached")
			.split(body())
			.to("direct:telegram");

		// Medições: dados do OpenAQ
		from("direct:measurements")
			.streamCaching()
			.setHeader("parameter", jsonpath("$.parameter"))
			.setHeader("unit", jsonpath("$.unit"))
			.setBody(jsonpath("$.value"))
			.process(this::processCitiesText)
			.to("direct:telegram");

		// Alertas
		from("direct:alerts")
			.streamCaching()
			.setHeader("parameter", jsonpath("$.parameter"))
			.setHeader("unit", jsonpath("$.unit"))
			.setBody(jsonpath("$.value"))
			.process(this::processCitiesAlerts)
			.to("kafka:{{smart-nazo.alert.topic}}");

		// Telegram
		from("direct:telegram")
			.streamCaching()
			.wireTap("direct:openaq-tap")
			.to("telegram:bots?authorizationToken={{smart-nazo.telegram.token}}");;
	}
}
