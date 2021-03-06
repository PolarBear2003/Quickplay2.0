package co.bugg.quickplay;

import co.bugg.quickplay.client.command.CommandHub;
import co.bugg.quickplay.client.command.CommandMain;
import co.bugg.quickplay.client.command.CommandQuickplay;
import co.bugg.quickplay.client.gui.InstanceDisplay;
import co.bugg.quickplay.client.gui.QuickplayGuiPartySpinner;
import co.bugg.quickplay.client.render.GlyphRenderer;
import co.bugg.quickplay.client.render.PlayerGlyph;
import co.bugg.quickplay.config.*;
import co.bugg.quickplay.games.Game;
import co.bugg.quickplay.games.PartyMode;
import co.bugg.quickplay.http.HttpRequestFactory;
import co.bugg.quickplay.http.Request;
import co.bugg.quickplay.http.response.ResponseAction;
import co.bugg.quickplay.http.response.WebResponse;
import co.bugg.quickplay.util.*;
import co.bugg.quickplay.util.analytics.AnalyticsRequest;
import co.bugg.quickplay.util.analytics.GoogleAnalytics;
import co.bugg.quickplay.util.analytics.GoogleAnalyticsFactory;
import co.bugg.quickplay.util.buffer.ChatBuffer;
import co.bugg.quickplay.util.buffer.MessageBuffer;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.command.ICommand;
import net.minecraft.util.*;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Mod(
        modid = Reference.MOD_ID,
        name = Reference.MOD_NAME,
        version = Reference.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.8, 1.8.9]",
        dependencies = "before:quickplaypremium@[1.0.1,]"
)
public class Quickplay {

    @Mod.Instance
    public static Quickplay INSTANCE = new Quickplay();

    /**
     * Whether the client is currently connected to the Hypixel network
     */
    public boolean onHypixel = false;
    /**
     * Whether the mod is currently enabled
     */
    public boolean enabled = false;
    /**
     * The reason the mod has been disabled, if it is disabled
     */
    public String disabledReason = null;
    /**
     * Verification method used to verify the client is online Hypixel, or null if not on Hypixel.
     */
    public ServerChecker.VerificationMethod verificationMethod;
    /**
     * Thread pool for blocking code
     */
    public final ExecutorService threadPool = Executors.newCachedThreadPool();
    /**
     * A list of all registered event handlers
     */
    public final List<Object> eventHandlers = new ArrayList<>();
    /**
     * A list of all registered commands
     */
    public final List<ICommand> commands = new ArrayList<>();
    /**
     * Buffer for sending messages to the client
     */
    public MessageBuffer messageBuffer;
    /**
     * Buffer for sending chat messages to the server
     */
    public ChatBuffer chatBuffer;
    /**
     * Factory for creating HTTP requests
     */
    public HttpRequestFactory requestFactory;
    /**
     * Factory for creating, loading, etc. of mod assets
     */
    public AssetFactory assetFactory;
    /**
     * List of games
     */
    public List<Game> gameList = new ArrayList<>();
    /**
     * InstanceWatcher that constantly watches for what Hypixel server instance the client is on
     */
    public InstanceWatcher instanceWatcher;
    /**
     * Display of the current Hypixel instance
     */
    public InstanceDisplay instanceDisplay;
    /**
     * Mods settings
     */
    public ConfigSettings settings;
    /**
     * Keybinds for the mod, and which buttons open what GUIs
     */
    public ConfigKeybinds keybinds;
    /**
     * Privacy settings for the mod's data collection
     */
    public ConfigUsageStats usageStats;
    /**
     * Whether the user should be asked if they want to share stats next time they join a server
     */
    public boolean promptUserForUsageStats = false;
    /**
     * Seconds between ping requests to the web server
     * 0 or less for no requests
     * If it becomes time for the mod to execute a request but
     * the frequency is equal to 0 or less, then all ping
     * requests will be cancelled until the mod re-enables (usually at restart).
     */
    public int pingFrequency = 0;
    /**
     * Thread containing code that's pinging the web server periodically
     */
    public Future pingThread;
    /**
     * Help menu for Quickplay Premium
     * retrieved from the <code>enable</code> endpoint on mod enable from the content field <code>premiumInfo</code>
     */
    public IChatComponent premiumAbout = null;
    /**
     * List of all player glyphs, which contains the URL to the glyph as well as the owner's UUID
     */
    public List<PlayerGlyph> glyphs = new ArrayList<>();
    /**
     * Quickplay's resource pack
     */
    public IResourcePack resourcePack;
    /**
     * Google Analytics API tracker
     */
    public GoogleAnalytics ga;
    /**
     * How many ping requests have been sent out
     */
    public int currentPing = 0;

    /**
     * Whether this client is premium or not
     * Verified from the web server
     */
    boolean premiumClient = false;
    /**
     * When Quickplay Premium expires for this user
     */
    public long expirationTime = 0;
    /**
     * URL to this Premium user's purchase page.
     */
    public String purchasePageURL = null;
    /**
     * Session key used for Premium-related resource requests
     */
    public String sessionKey;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // The message buffer should remain online even
        // if the mod is disabled - this allows for
        // communicating important information about why
        // the mod is currently disabled, or how to fix.
        messageBuffer = (MessageBuffer) new MessageBuffer(100).start();
        enable();
    }

    /**
     * Register a specific object as an event handler
     * @param handler Object to register
     */
    public void registerEventHandler(Object handler) {
        if(!eventHandlers.contains(handler)) {
            eventHandlers.add(handler);
        }
        MinecraftForge.EVENT_BUS.register(handler);
    }

    /**
     * Unregister a specific object as an event handler
     * @param handler Object to unregister
     */
    public void unregisterEventHandler(Object handler) {
        eventHandlers.remove(handler);
        MinecraftForge.EVENT_BUS.unregister(handler);
    }

    /**
     * Enable the mod
     */
    public void enable() {
        if(!this.enabled) {

            this.enabled = true;
            requestFactory = new HttpRequestFactory();
            assetFactory = new AssetFactory();

            assetFactory.createDirectories();
            assetFactory.dumpOldCache();
            resourcePack = assetFactory.registerResourcePack();

            try {
                settings = (ConfigSettings) AConfiguration.load("settings.json", ConfigSettings.class);
                keybinds = (ConfigKeybinds) AConfiguration.load("keybinds.json", ConfigKeybinds.class);
                usageStats = (ConfigUsageStats) AConfiguration.load("privacy.json", ConfigUsageStats.class);
            } catch (IOException | JsonSyntaxException e) {
                // Config either doesn't exist or couldn't be parsed
                e.printStackTrace();
                assetFactory.createDirectories();

                if(settings == null) {
                    settings = new ConfigSettings();
                }
                if(keybinds == null) {
                    keybinds = new ConfigKeybinds(true);
                }
                if(usageStats == null) {
                    promptUserForUsageStats = true;
                }

                try {
                    // Write the default config that we just made to save it
                    settings.save();
                    keybinds.save();
                } catch (IOException e1) {
                    // File couldn't be saved
                    e1.printStackTrace();
                    sendExceptionRequest(e1);
                    Quickplay.INSTANCE.messageBuffer.push(new Message(new ChatComponentTranslation(
                            "quickplay.config.saveerror").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED))));
                }
            }

            // Create new Google Analytics instance if possible
            if(usageStats != null && usageStats.statsToken != null) {
                createGoogleAnalytics();
            }

            // Send analytical data to Google
            if(usageStats != null && usageStats.statsToken != null && usageStats.sendUsageStats && ga != null) {
                threadPool.submit(() -> {
                    try {
                        ga.createEvent("Systematic Events", "Mod Enable")
                                .setSessionControl(AnalyticsRequest.SessionControl.START)
                                .send();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }

            // Try to load the previous game list from cache
            // Web server will probably instruct to reload if it's available
            try {
                final Game[] gameListArray = this.assetFactory.loadCachedGamelist();
                if(gameListArray != null) {
                    this.gameList = java.util.Arrays.asList(gameListArray);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendExceptionRequest(e);
            }

            this.threadPool.submit(() -> {
                final Request request = requestFactory.newEnableRequest();
                if(request != null) {
                    final WebResponse response = request.execute();

                    if (response != null) {
                        for (ResponseAction action : response.actions) {
                            action.run();
                        }

                        try {
                            if (response.ok && response.content != null) {
                                // Add the premium about information
                                if(response.content.getAsJsonObject().get("premiumInfo") != null) {
                                    premiumAbout = IChatComponent.Serializer
                                            .jsonToComponent(response.content.getAsJsonObject().get("premiumInfo").toString());
                                }
                                // Add all glyphs
                                if(response.content.getAsJsonObject().get("glyphs") != null) {
                                    QuickplayEventHandler.mainThreadScheduledTasks.add(() -> {
                                        glyphs.addAll(Arrays.asList(new Gson().fromJson(response.content
                                                .getAsJsonObject().get("glyphs"), PlayerGlyph[].class)));
                                    });
                                }
                            }
                        } catch (IllegalStateException e) {
                            e.printStackTrace();
                            sendExceptionRequest(e);
                        }
                    }
                }
            });

            // Check for Premium subscription
            try {
                verifyPremium();
            } catch (IOException | NoSubscriptionException e) {
                e.printStackTrace();
            }

            registerEventHandler(new GlyphRenderer());
            registerEventHandler(new QuickplayEventHandler());

            chatBuffer = (ChatBuffer) new ChatBuffer(100).start();
            instanceWatcher = new InstanceWatcher(30).start();
            instanceDisplay = new InstanceDisplay();

            commands.add(new CommandQuickplay());

            if(settings.redesignedLobbyCommand) {
                // Register lobby commands
                commands.add(new CommandHub("l"));
                commands.add(new CommandHub("lobby"));
                commands.add(new CommandHub("hub"));
                commands.add(new CommandHub("spawn"));
                commands.add(new CommandHub("leave"));
                commands.add(new CommandMain("main"));
            }
            // Copy of the lobby command that doesn't override server commands
            // Used for "Go to Lobby" buttons
            commands.add(new CommandHub("quickplaylobby", "lobby"));
            commands.forEach(ClientCommandHandler.instance::registerCommand);
        }
    }

    /**
     * Create the Google Analytics instance with customized settings for this Quickplay instance
     */
    public void createGoogleAnalytics() {
        ga = GoogleAnalyticsFactory.create(Reference.ANALYTICS_TRACKING_ID, usageStats.statsToken.toString(),
                Reference.MOD_NAME, Reference.VERSION);
        final AnalyticsRequest defaultRequest = ga.getDefaultRequest();

        defaultRequest.setLanguage(String.valueOf(Minecraft.getMinecraft().gameSettings.language).toLowerCase());

        final GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        defaultRequest.setScreenResolution(screen.getDisplayMode().getWidth() + "x" + screen.getDisplayMode().getHeight());

        // Determine User-Agent/OS
        // Example: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/64.0.3282.186 Safari/537.36
        final String systemDetails = System.getProperty("os.name") + " " + System.getProperty("os.version") + "; " + System.getProperty("os.arch");
        defaultRequest.setUserAgent("Mozilla/5.0 (" + systemDetails + ") " + Reference.MOD_NAME + " " + Reference.VERSION);
    }

    /**
     * Disable the mod
     */
    public void disable(String reason) {
        // TODO This gets stuck when event handlers are unregistered.
        if(this.enabled) {
            this.enabled = false;
            eventHandlers.forEach(this::unregisterEventHandler);
            this.disabledReason = reason;

            if(chatBuffer != null) {
                chatBuffer.stop();
            }

            if(instanceWatcher != null) {
                instanceWatcher.stop();
            }
        }
    }

    /**
     * Check if the mod is enabled, and
     * send a disabled message if not.
     * @return Whether the mod is enabled
     */
    public boolean checkEnabledStatus() {
        if(!enabled) {
            IChatComponent message = new ChatComponentTranslation("quickplay.disabled", this.disabledReason);
            message.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));

        }

        return enabled;
    }

    /**
     * Reorganizes a game list to obey priorities in {@link #settings}
     * @param gameList list of games to organize
     * @return organized list
     */
    public static Game[] organizeGameList(Game[] gameList) {
        return Arrays.stream(gameList)
                .sorted(Comparator.comparing(game -> Quickplay.INSTANCE.settings.gamePriorities
                        .getOrDefault(((Game) game).unlocalizedName, 0)).reversed())
                .toArray(Game[]::new);
    }

    /**
     * Send an exception request to Quickplay backend for error reporting
     * @param e Exception that occurred
     */
    public void sendExceptionRequest(Exception e) {
        if(usageStats != null && usageStats.sendUsageStats) {
            final WebResponse response = requestFactory.newExceptionRequest(e).execute();
            if(response != null) {
                for (ResponseAction action : response.actions)
                    action.run();
            }
            if(ga != null) {
                try {
                    ga.createException().setExceptionDescription(e.getMessage()).send();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    /**
     * Start a party mode session by randomizing selected games & then executing the command
     */
    public void launchPartyMode() {
        if(settings.partyModes.size() > 0) {
            if(settings.partyModeGui) {
                Minecraft.getMinecraft().displayGuiScreen(new QuickplayGuiPartySpinner());
            } else {
                // No GUI, handle randomization in chat

                // Send commencement message if delay is greater than 0 seconds
                if(settings.partyModeDelay > 0) {
                    messageBuffer.push(new Message(new ChatComponentTranslation("quickplay.party.commencing")
                            .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.LIGHT_PURPLE))));
                }

                // Calculate mode
                PartyMode mode;
                // Don't need to randomize on size 1
                if(settings.partyModes.size() == 1) {
                    mode = settings.partyModes.get(0);
                } else {
                    final Random random = new Random();
                    mode = settings.partyModes.get(random.nextInt(settings.partyModes.size()));
                }

                try {
                    Thread.sleep((long) (settings.partyModeDelay * 1000));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                messageBuffer.push(new Message(new ChatComponentTranslation("quickplay.party.sendingYou", mode.name)
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN))));
                chatBuffer.push(mode.command);
            }
        } else {
            messageBuffer.push(new Message(new ChatComponentTranslation("quickplay.party.nogames")
                    .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED))));
        }
    }

    /**
     * Reload the Quickplay Resource pack that contains glyphs, icons, lang, etc.
     * @throws NoSuchFieldException Neither field (obf or deobf) exist, according to the client.
     */
    public void reloadResourcePack() throws NoSuchFieldException, IllegalAccessException {
        Field resourceManagerField;
        try {
            resourceManagerField = Minecraft.class.getDeclaredField("field_110451_am");
        } catch(NoSuchFieldException e) {
            resourceManagerField = Minecraft.class.getDeclaredField("mcResourceManager");
        }
        resourceManagerField.setAccessible(true);
        SimpleReloadableResourceManager resourceManager = (SimpleReloadableResourceManager) resourceManagerField.get(Minecraft.getMinecraft());

        resourceManager.reloadResourcePack(resourcePack);
    }

    /**
     * Reload the provided resourceLocation with the provided file
     * @param file The file of the newly changed resource
     * @param resourceLocation The resourceLocation to change/set
     */
    public void reloadResource(File file, ResourceLocation resourceLocation) {
        if (file != null && file.exists()) {

            TextureManager texturemanager = Minecraft.getMinecraft().getTextureManager();
            texturemanager.deleteTexture(resourceLocation);
            ITextureObject object = new ThreadDownloadImageData(file, null, resourceLocation, null);
            texturemanager.loadTexture(resourceLocation, object);
        }
    }

    /**
     * Verify whether this client has a Quickplay Premium subscription, and authenticate
     * {@link #expirationTime} and {@link #premiumClient} are set by this method.
     * This method also returns {@link #premiumClient} on completion.
     * @throws NoSubscriptionException when the client attempts to get a handshake secret,
     *      but doesnt have a subscription.
     */
    public boolean verifyPremium() throws IOException, NoSubscriptionException {

        final PremiumAuthenticator authenticator = new PremiumAuthenticator();
        final String secret = authenticator.getHandshakeSecret();

        authenticator.sendSessionServerRequest(secret);

        final Request request = requestFactory.premiumVerificationRequest();
        if(request != null) {
            final WebResponse response = request.execute();
            if(response != null && response.ok) {
                if(response.actions != null)
                    for(final ResponseAction action : response.actions)
                        action.run();

                if(response.content != null && response.content.getAsJsonObject().get("premium") != null) {
                    this.premiumClient = response.content.getAsJsonObject().get("premium").getAsBoolean();

                    if(response.content.getAsJsonObject().get("sessionKey") != null) {
                        this.sessionKey = response.content.getAsJsonObject().get("sessionKey").getAsString();
                    }

                    // Reauthenticate just before the session expires
                    if(response.content.getAsJsonObject().get("sessionExpriesIn") != null)
                        Quickplay.INSTANCE.threadPool.submit(() -> {
                            try {
                                // Sleep until 5 minutes before the session expires, or for at least 5 minutes.
                                Thread.sleep(Math.max(response.content.getAsJsonObject().get("sessionExpiresIn")
                                        .getAsLong() - 300000, 300000));
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            try {
                                verifyPremium();
                            } catch (IOException | NoSubscriptionException e) {
                                e.printStackTrace();
                            }
                        });

                    if(this.premiumClient && response.content.getAsJsonObject().get("expires") != null) {
                        this.expirationTime = response.content.getAsJsonObject().get("expires").getAsLong();
                    }
                    if(this.premiumClient && response.content.getAsJsonObject().get("purchasePage") != null) {
                        this.purchasePageURL = response.content.getAsJsonObject().get("purchasePage").getAsString();
                    }
                } else {
                    this.premiumClient = false;
                }

                return this.premiumClient;
            } else {
                throw new IOException("Failed to verify Premium: null or not-ok response from web server.");
            }
        } else {
            throw new RuntimeException("Null request was returned by PremiumRequestFactory#premiumVerificationRequest()");
        }

    }
}
