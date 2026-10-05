package com.altronixsoft.workflow.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The mail that asks a customer for what is missing. Plain templates, no model: the wording is fixed, only the
 * list of fields changes.
 */
final class ClarificationMessage {

    private record Texts(String intro, String outro, Map<String, String> fields) {}

    private static final Map<String, Texts> TEXTS = Map.of(
            "en",
            new Texts(
                    "Thank you for your request. To prepare a quote we still need:",
                    "Please reply to this email with the details.\n\nBest regards\nNordline",
                    Map.of(
                            "origin", "the pickup location",
                            "destination", "the delivery location",
                            "pallets", "the number of pallets",
                            "weightKg", "the total weight in kg",
                            "pickupDate", "the pickup date")),
            "de",
            new Texts(
                    "Vielen Dank für Ihre Anfrage. Für ein Angebot benötigen wir noch:",
                    "Bitte antworten Sie auf diese E-Mail mit den Angaben.\n\nMit freundlichen Grüßen\nNordline",
                    Map.of(
                            "origin", "den Abholort",
                            "destination", "den Lieferort",
                            "pallets", "die Anzahl der Paletten",
                            "weightKg", "das Gesamtgewicht in kg",
                            "pickupDate", "das Abholdatum")),
            "pl",
            new Texts(
                    "Dziękujemy za zapytanie. Aby przygotować wycenę, potrzebujemy jeszcze:",
                    "Prosimy o odpowiedź na tę wiadomość z danymi.\n\nPozdrawiamy\nNordline",
                    Map.of(
                            "origin", "miejsca odbioru",
                            "destination", "miejsca dostawy",
                            "pallets", "liczby palet",
                            "weightKg", "całkowitej wagi w kg",
                            "pickupDate", "daty odbioru")),
            "uk",
            new Texts(
                    "Дякуємо за запит. Щоб підготувати пропозицію, нам ще потрібні:",
                    "Будь ласка, дайте відповідь на цей лист із даними.\n\nЗ повагою\nNordline",
                    Map.of(
                            "origin", "місце завантаження",
                            "destination", "місце доставки",
                            "pallets", "кількість палет",
                            "weightKg", "загальна вага в кг",
                            "pickupDate", "дата завантаження")),
            "ru",
            new Texts(
                    "Спасибо за запрос. Чтобы подготовить предложение, нам ещё нужны:",
                    "Пожалуйста, ответьте на это письмо с данными.\n\nС уважением\nNordline",
                    Map.of(
                            "origin", "место погрузки",
                            "destination", "место доставки",
                            "pallets", "количество паллет",
                            "weightKg", "общий вес в кг",
                            "pickupDate", "дата погрузки")));

    private ClarificationMessage() {}

    static String render(String language, List<String> missing) {
        Texts t = TEXTS.getOrDefault(language == null ? "en" : language.toLowerCase(Locale.ROOT), TEXTS.get("en"));
        String list = missing.stream()
                .map(field -> "- " + t.fields().getOrDefault(field, field))
                .collect(Collectors.joining("\n"));
        return t.intro() + "\n\n" + list + "\n\n" + t.outro();
    }
}
