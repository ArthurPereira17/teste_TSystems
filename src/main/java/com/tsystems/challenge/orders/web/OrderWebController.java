package com.tsystems.challenge.orders.web;

import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Controller
public class OrderWebController {
    private final OrderService orderService;

    public OrderWebController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/")
    public String dashboard(
            @RequestParam(name = "created", required = false) UUID createdOrderId,
            Model model
    ) {
        if (!model.containsAttribute("orderForm")) {
            model.addAttribute("orderForm", new CreateOrderForm());
        }
        model.addAttribute("createdOrderId", createdOrderId);
        addDashboardData(model);
        return "orders";
    }

    @PostMapping("/ui/orders")
    public String create(
            @Valid @ModelAttribute("orderForm") CreateOrderForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            addDashboardData(model);
            return "orders";
        }

        try {
            Order order = orderService.create(form.toRequest());
            redirectAttributes.addAttribute("created", order.id());
            return "redirect:/";
        } catch (RuntimeException ex) {
            model.addAttribute(
                    "integrationError",
                    "The order could not be accepted. Review how the application handles Pricing API failures."
            );
            addDashboardData(model);
            return "orders";
        }
    }

    /** Explicit recovery action from the dashboard: a plain HTML form submission, no JavaScript. */
    @PostMapping("/ui/orders/{id}/retry")
    public String retry(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        orderService.retryPricingNow(id);
        redirectAttributes.addAttribute("created", id);
        return "redirect:/";
    }

    private void addDashboardData(Model model) {
        List<Order> orders = orderService.list().stream()
                .sorted(Comparator.comparing(Order::createdAt).reversed())
                .toList();

        long confirmed = orders.stream().filter(order -> order.status() == OrderStatus.CONFIRMED).count();
        long pending = orders.stream().filter(order -> order.status() == OrderStatus.PENDING_PRICING).count();
        long needsAttention = orders.stream().filter(order -> order.status() == OrderStatus.PRICING_FAILED).count();

        model.addAttribute("orders", orders);
        model.addAttribute("orderCount", orders.size());
        model.addAttribute("confirmedCount", confirmed);
        model.addAttribute("pendingCount", pending);
        model.addAttribute("attentionCount", needsAttention);
    }
}
