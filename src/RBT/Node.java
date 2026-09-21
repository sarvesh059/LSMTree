package RBT;

public class Node<K,V> {
    private K key;
    private V val;
    private Node<K,V> left;
    private Node<K,V> right;
    private boolean red;

    public Node(K key, V val){
        this.key = key;
        this.val = val;
        this.red = true;
    }

    public K getKey(){
        return this.key;
    }

    public void setKey(K key){
        this.key = key;
    }

    public V getVal(){
        return this.val;
    }

    public void setVal(V val){
        this.val = val;
    }

    public Node<K,V> getLeft(){
        return this.left;
    }

    public void setLeft(Node<K,V> node){
        this.left = node;
    }

    public Node<K,V> getRight(){
        return this.right;
    }

    public void setRight(Node<K,V> node){
        this.right = node;
    }

    public void makeNodeRed(){
        this.red = true;
    }

    public void makeNodeBlack(){
        this.red = false;
    }

    public boolean isRed(){
        return this.red;
    }

    public void flipColor(){
        this.red = !this.red;
    }

    public boolean isLeftChildRed(){
        return this.getLeft() != null && this.getLeft().isRed();
    }

    public boolean isLeftChildBlack(){
        return !isLeftChildRed();
    }

    public boolean isRightChildRed(){
        return this.getRight() != null && this.getRight().isRed();
    }

    public boolean isRightChildBlack(){
        return !isRightChildRed();
    }

    public Node<K,V> clone(){
        Node<K,V> node = new Node<>(this.key, this.val);
        node.setLeft(this.left);
        node.setRight(this.right);
        if(this.isRed()){
            node.makeNodeRed();
        }else{
            node.makeNodeBlack();
        }

        return node;
    }
}
