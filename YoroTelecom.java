import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

record Subscriber(
    UUID id,
    String name,
    BigDecimal balance,
    List<Subscription> activeSubscriptions
) {}

record Subscription(
    UUID id,
    String name,
    BigDecimal basePrice,
    SubscriptionType type
) {}

enum SubscriptionType { MUSIC, INTERNET, TV }

// Exception handlers
class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String message) {
        super(message);
    }
}

class SubscriptionNotFoundException extends RuntimeException {
    public SubscriptionNotFoundException(String message) {
        super(message);
    }
}

class SubscriberNotFoundException extends RuntimeException{
    public SubscriberNotFoundException(String message){
        super(message);
    }
}

class DuplicateSubscriptionException extends RuntimeException {
    public DuplicateSubscriptionException(String message){
        super(message);
    }
}

class MissingSubscriptionException extends RuntimeException{
    public MissingSubscriptionException(String message){
        super(message);
    }
}

interface Billable {
    BigDecimal calculateTotal(List<Subscription> subscriptions);
    
    default BigDecimal applyDiscount(BigDecimal amount, double percent) {
        if (amount == null){
            throw new IllegalArgumentException("Amount cannot be null");
        }
        return amount.multiply(BigDecimal.valueOf(1 - percent / 100));
    }

    default boolean hasAllTypes(List<Subscription> subscriptions){
        if (subscriptions == null){
            throw new IllegalArgumentException("List cannot be null");
        }
        Set<SubscriptionType> types = subscriptions.stream()
            .map(Subscription::type)
            .collect(Collectors.toSet()); // not list for the case if it will be possible for user to have 2 same subs
        
        return types.size() == SubscriptionType.values().length;    
         
    }
}

interface Repository<T> {
    Optional<T> findById(UUID id);
    List<T> findAll();
    void save(T entity);
    void delete(UUID id);
}

class SubscriberRepository implements Repository<Subscriber>{
    private final Map<UUID, Subscriber> clients = new HashMap<>();

    @Override
    public Optional<Subscriber> findById(UUID id){
        return Optional.ofNullable(clients.get(id));
    }

    @Override
    public List<Subscriber> findAll(){
        return new ArrayList<>(clients.values());
    }

    @Override
    public void save(Subscriber entity){
        clients.put(entity.id(), entity);
    }

    @Override
    public void delete(UUID id){
        clients.remove(id);
    }
}

class SubscriptionRepository implements Repository<Subscription> {
    private final Map<UUID, Subscription> storage = new HashMap<>();
    
    @Override
     public Optional<Subscription> findById(UUID id) {
        return Optional.ofNullable(storage.get(id));
    }
   
    @Override
    public List<Subscription> findAll() {
        return new ArrayList<>(storage.values());        

    }
    
    @Override
    public void save(Subscription entity) {
        storage.put(entity.id(), entity);
    }
    
    @Override
    public void delete(UUID id){
        storage.remove(id);
    }

    // Search by type
    public List<Subscription> findByType(SubscriptionType type){
        return storage.values().stream()
            .filter(s -> s.type() == type)
            .collect(Collectors.toList());
    }

    // Search by name or name part
    public List<Subscription> findByName(String searchString){
        return storage.values().stream()
            .filter(s -> s.name().toLowerCase().contains(searchString.toLowerCase()))
            .collect(Collectors.toList());
    }
}

// here all the payments and subscription operations are happening
class BillingService implements Billable {
    private final Repository<Subscription> subscriptionRepo;
    private final Repository<Subscriber> clientRepo;
    private final List<Operation> operationHistory = new ArrayList<>();

    record Operation(
        UUID subscriberId,
        OperationType type,
        BigDecimal amount,
        LocalDateTime timestamp,
        String description
    ) {}

    enum OperationType { DEBIT, CREDIT, SUBSCRIBE, UNSUBSCRIBE }

    public BillingService(Repository<Subscription> subRepo, Repository<Subscriber> clientRepo) {
        this.subscriptionRepo = subRepo;
        this.clientRepo = clientRepo;
    }
    
    public void subscribe(UUID subscriberId, UUID subscriptionId) 
            throws InsufficientFundsException {
                
                // Checking if subscription exists
                Subscription subscription = subscriptionRepo.findById(subscriptionId)
                    .orElseThrow(() -> new SubscriptionNotFoundException("Subscrition not found: " + subscriptionId));
                
                Subscriber subscriber = clientRepo.findById(subscriberId)
                    .orElseThrow(() -> new SubscriberNotFoundException("Subscriber not found: " + subscriberId));    

                // Checking if user already has this sub 
                boolean alreadySubscribed = subscriber.activeSubscriptions().stream()
                    .anyMatch(s -> s.id().equals(subscriptionId));

                if (alreadySubscribed) {
                    throw new DuplicateSubscriptionException("This client already has this subscription");
                }
                
                // getting user balance and sub price and checking if they can afford it
                BigDecimal UserBalance = subscriber.balance();
                BigDecimal Price = subscription.basePrice();

                if (subscriber.balance().compareTo(Price) < 0){ // I was said, you can't operate with basic mathematical operands because this way is not accurate :0
                    throw new InsufficientFundsException(String.format("Insufficient funds on balance of %s, %.2f more needed", subscriber.name(), Price.subtract(subscriber.balance())));
                }
                                
                // adding new active subscription to the subscriber    
                List<Subscription> updatedList = new ArrayList<>(subscriber.activeSubscriptions());
                updatedList.add(subscription);
                Subscriber updatedSubscriber = new Subscriber(subscriber.id(), subscriber.name(), subscriber.balance().subtract(Price), updatedList);   
                clientRepo.save(updatedSubscriber);
               
                // adding buy record operation history 
                logOperation(subscriberId, OperationType.SUBSCRIBE, Price, "Subscribed to: " + subscription.name());
    }

    public void unsubsribe(UUID subscriberId, UUID subscriptionId){
        Subscriber subscriber = clientRepo.findById(subscriberId)
            .orElseThrow(() -> new SubscriberNotFoundException("Subscriber not found"));
        
        List<Subscription> activeSubscriptions = new ArrayList<>(subscriber.activeSubscriptions()); 
        
        if (!activeSubscriptions.stream().anyMatch(s -> s.id() == subscriptionId)){
            throw new MissingSubscriptionException(String.format("Subscription missing for user %s", subscriber.name()));
        }
      
        Subscription formerSub = activeSubscriptions.stream()
            .filter(s -> s.id().equals(subscriptionId))
            .findFirst()
            .orElseThrow(() -> new MissingSubscriptionException("Subscription missing"));

        subscriber.activeSubscriptions().stream()
        .filter(s -> s.id().equals(subscriptionId))
        .findFirst();
        
        activeSubscriptions.removeIf(s -> s.id() == subscriptionId);
        Subscriber updatedSubscriber = new Subscriber(subscriber.id(), subscriber.name(), subscriber.balance(), activeSubscriptions);
        clientRepo.save(updatedSubscriber);

        logOperation(subscriberId, OperationType.UNSUBSCRIBE, BigDecimal.ZERO, "Unsubsribed from " + formerSub.name());

    }

    private void logOperation(UUID subscriberid, OperationType type, BigDecimal qty, String desciption){
        Operation op = new Operation(subscriberid, type, qty, LocalDateTime.now(), desciption);
        operationHistory.add(op);
    }

    // total quanity of all the subscribtions with discount calculation 
    @Override
    public BigDecimal calculateTotal(List<Subscription> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()){         
            return BigDecimal.ZERO;
        }
        BigDecimal totalQty = subscriptions.stream()
            .map(Subscription::basePrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        if (hasAllTypes(subscriptions)){
            totalQty = applyDiscount(totalQty, 15); // applying a 15% discount if the client has all 3 types if subscriptions
        }

        return totalQty;
        
    }
    
    public List<Operation> getOpHistory(){
        return new ArrayList<>(operationHistory); // to return new array and not a link to a real list
    }

    public List<Operation> getHistoryBySubscriber(UUID subscriberId) {
        List<Operation> allClientOps = operationHistory.stream() 
                                            .filter(op -> op.subscriberId().equals(subscriberId))
                                            .collect(Collectors.toList()); 
        return allClientOps;
    }
    
    // balance operations, client's balance is edited, new client entity is being created and then saved()
    public void creditAccount(UUID subscriberId, BigDecimal Qty){
        if (Qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit ammount has to be positive");
        }  

        Subscriber subscriber = clientRepo.findById(subscriberId).orElseThrow(() -> new RuntimeException("Subscriber not found"));
        Subscriber updatedSubscriber = new Subscriber(subscriberId, 
                                                      subscriber.name(), 
                                                      subscriber.balance().add(Qty), 
                                                      subscriber.activeSubscriptions());

        clientRepo.save(updatedSubscriber);
        logOperation(subscriberId, OperationType.CREDIT, Qty, "Account credit");
    }

    public void debitAccount(UUID subscriberId, BigDecimal Qty)
        throws InsufficientFundsException {
        
        Subscriber subscriber = clientRepo.findById(subscriberId).orElseThrow(() -> new RuntimeException("Subscriber not found"));
        if (subscriber.balance().compareTo(Qty) < 0){
            throw new InsufficientFundsException(String.format("Insufficient fund on balance %s, %.2f more needed", subscriber.name(), Qty.subtract(subscriber.balance())));
        }
        
        Subscriber updatedSubscriber = new Subscriber(subscriber.id(), 
                                                    subscriber.name(), 
                                                    subscriber.balance().subtract(Qty), 
                                                    subscriber.activeSubscriptions());

        clientRepo.save(updatedSubscriber);
        logOperation(subscriberId, OperationType.DEBIT, Qty, "Account debit");
    }
   

}



public class YoroTelecom {
    public static void main(String[] args) {
        // repos
        Repository<Subscription> subRepo = new SubscriptionRepository();
        Repository<Subscriber> clientRepo = new SubscriberRepository();
        
        // subscriptions
        Subscription music = new Subscription(UUID.randomUUID(), "Music Unlimited", new BigDecimal("10"), SubscriptionType.MUSIC);
        Subscription internet = new Subscription(UUID.randomUUID(), "Internet 10GB", new BigDecimal("20"), SubscriptionType.INTERNET);
        Subscription tv = new Subscription(UUID.randomUUID(), "TV Premium", new BigDecimal("15"), SubscriptionType.TV);
        
        subRepo.save(music);
        subRepo.save(internet);
        subRepo.save(tv);
        
        // let's add Steve 
        Subscriber steve = new Subscriber(UUID.randomUUID(), "Steve None", new BigDecimal("100.00"), new ArrayList<>());
        clientRepo.save(steve);
                
        BillingService billing = new BillingService(subRepo, clientRepo);
        
        // operations
        try {
            billing.subscribe(steve.id(), music.id());
            billing.subscribe(steve.id(), internet.id());
            billing.subscribe(steve.id(), tv.id());  
            
            System.out.println("Total with discount: $" + billing.calculateTotal(clientRepo.findById(steve.id())
                .orElseThrow().activeSubscriptions()));
            
            billing.debitAccount(steve.id(), new BigDecimal("10"));
            billing.creditAccount(steve.id(), new BigDecimal("50"));
            
        } catch (InsufficientFundsException e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        // second test, broke ver

        Subscriber bob = new Subscriber(UUID.randomUUID(), "bbno$", new BigDecimal("35"), new ArrayList<>());
        clientRepo.save(bob);

        try{
            billing.subscribe(bob.id(), music.id());
            billing.subscribe(bob.id(), internet.id());
            billing.unsubsribe(bob.id(), internet.id());
            // here he runs out of money
            billing.subscribe(bob.id(), tv.id());
        } catch (InsufficientFundsException | MissingSubscriptionException e) {
            System.err.println("Error: " + e.getMessage());
        } 
 
        // listing history of all clients 
        List<Subscriber> subscribers = clientRepo.findAll();
        System.out.println();
        for (Subscriber subscriber : subscribers){
            System.out.println(String.format("%s billing history: ", subscriber.name()));
            billing.getHistoryBySubscriber(subscriber.id()).forEach(op -> System.out.println(op.timestamp() + " " + op.type() + " $" + op.amount()));
            System.out.println();
        }

    }
}